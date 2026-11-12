"""Explainable posture classification and temporal fall verification built on YOLO Pose keypoints.

Pipeline per tracked person:
  1. ``extract_features``  - geometric features from the 17 COCO keypoints + bounding box.
  2. ``classify_posture``  - STANDING / WALKING / SITTING / BENDING / LYING / ON_FLOOR / UNKNOWN.
  3. ``TrackState``        - per-person state machine:
        NORMAL --(upright -> down within a short window, with a large drop)--> VERIFYING   ("potential fall")
        VERIFYING --(enough observed time on the ground, no recovery)--> CONFIRMED
        VERIFYING --(person gets up / evidence says not down)--> CANCELLED
        VERIFYING --(video ends, or person not visible enough)--> INCONCLUSIVE

A single horizontal frame never confirms a fall: confirmation needs a fall-like transition *and* persistence on the ground
for the verification period. All thresholds live in ``FallConfig`` so they can be tuned and explained.
"""
from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass
from statistics import median
from typing import Deque, List, Optional, Sequence

# COCO keypoint indices used by YOLO Pose
NOSE = 0
L_SHOULDER, R_SHOULDER = 5, 6
L_HIP, R_HIP = 11, 12
L_KNEE, R_KNEE = 13, 14
L_ANKLE, R_ANKLE = 15, 16

STANDING, WALKING, SITTING, BENDING = "STANDING", "WALKING", "SITTING", "BENDING"
LYING, ON_FLOOR, UNKNOWN = "LYING", "ON_FLOOR", "UNKNOWN"
UPRIGHT = {STANDING, WALKING, BENDING}
DOWN = {LYING, ON_FLOOR}


@dataclass(frozen=True)
class FallConfig:
    keypoint_confidence: float = 0.35      # minimum keypoint score to trust a point
    lying_torso_angle: float = 55.0        # torso this far from vertical (deg) is a lying candidate
    lying_leg_angle: float = 45.0          # ...and legs this far from vertical
    upright_torso_angle: float = 30.0      # torso within this of vertical is upright
    bending_torso_angle: float = 35.0      # torso tilted while legs stay vertical -> bending
    sitting_thigh_angle: float = 50.0      # thighs this far from vertical while torso upright -> sitting
    lying_aspect_ratio: float = 1.15       # bbox width/height fallback when keypoints are poor
    walking_speed: float = 0.25            # body-heights per second of horizontal hip motion that counts as walking
    # potential-fall trigger
    fall_window_seconds: float = 2.5       # max time between the last upright pose and the first down pose
    min_hip_drop: float = 0.15             # hip must drop this many standing heights...
    max_height_ratio: float = 0.75         # ...or the body box must shrink below this fraction of standing height
    trigger_score: float = 0.50            # minimum fall-transition score to start verification
    min_trigger_keypoints: int = 8         # the down pose must be well observed to start a verification
    chair_fall_rotation: float = 60.0      # from sitting, torso must rotate this many degrees within the last second
    # verification
    verification_seconds: float = 5.0      # how long the person must be observed down
    recovery_seconds: float = 1.0          # continuous upright time that cancels a verification
    min_observed_fraction: float = 0.5     # observed down-time needed = this x verification window
    max_extension_factor: float = 3.0      # keep collecting evidence up to this x window when visibility is poor
    confirm_down_ratio: float = 0.70       # fraction of observations that must be "down"
    cancel_down_ratio: float = 0.40        # below this the potential fall is cancelled


# ----------------------------------------------------------------------------- geometry

@dataclass
class PoseFeatures:
    visible: int                   # keypoints above the confidence threshold
    quality: float                 # mean confidence of the visible keypoints
    bbox: Sequence[float]          # x1, y1, x2, y2
    aspect: float                  # bbox width / height
    torso_angle: Optional[float]   # 0 = upright, 90 = horizontal, 180 = upside down
    leg_angle: Optional[float]     # hip->ankle (or knee) from vertical
    thigh_angle: Optional[float]   # hip->knee from vertical
    body_angle: Optional[float]    # head/shoulders -> feet from vertical
    hip: Optional[tuple]           # hip mid-point (x, y)
    ankle: Optional[tuple]         # ankle mid-point (x, y)
    head_y: Optional[float]
    feet_y: Optional[float]
    torso_length: Optional[float]
    truncated: bool = False        # box touches the image border: body only partly visible

    @property
    def height(self) -> float:
        return max(1.0, self.bbox[3] - self.bbox[1])


def _mid(kps, idx: Sequence[int], thr: float):
    pts = [(kps[i][0], kps[i][1]) for i in idx if i < len(kps) and kps[i][2] >= thr]
    if not pts:
        return None
    return (sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))


def _angle_from_vertical(top, bottom) -> Optional[float]:
    """Angle (deg) of the segment top->bottom from the downward vertical; 0 = top directly above bottom."""
    if top is None or bottom is None:
        return None
    dx, dy = bottom[0] - top[0], bottom[1] - top[1]
    if abs(dx) < 1e-6 and abs(dy) < 1e-6:
        return None
    return math.degrees(math.atan2(abs(dx), dy))  # dy > 0 means bottom is lower in the image


def extract_features(keypoints: Sequence[Sequence[float]], bbox: Sequence[float], cfg: FallConfig,
                     frame_size: Optional[tuple] = None) -> PoseFeatures:
    thr = cfg.keypoint_confidence
    scores = [p[2] for p in keypoints if len(p) >= 3 and p[2] >= thr]
    shoulders = _mid(keypoints, (L_SHOULDER, R_SHOULDER), thr)
    hips = _mid(keypoints, (L_HIP, R_HIP), thr)
    knees = _mid(keypoints, (L_KNEE, R_KNEE), thr)
    ankles = _mid(keypoints, (L_ANKLE, R_ANKLE), thr)
    head = _mid(keypoints, (NOSE, 1, 2, 3, 4), thr) or shoulders
    feet = ankles or knees
    x1, y1, x2, y2 = bbox
    truncated = False
    if frame_size:
        w, h = frame_size
        margin = 3
        truncated = x1 <= margin or y1 <= margin or x2 >= w - margin or y2 >= h - margin
    return PoseFeatures(
        visible=len(scores),
        quality=(sum(scores) / len(scores)) if scores else 0.0,
        bbox=bbox,
        aspect=(x2 - x1) / max(1.0, y2 - y1),
        torso_angle=_angle_from_vertical(shoulders, hips),
        leg_angle=_angle_from_vertical(hips, feet),
        thigh_angle=_angle_from_vertical(hips, knees),
        body_angle=_angle_from_vertical(head, feet),
        hip=hips,
        ankle=ankles,
        head_y=head[1] if head else None,
        feet_y=feet[1] if feet else None,
        torso_length=math.dist(shoulders, hips) if shoulders and hips else None,
        truncated=truncated,
    )


def classify_posture(f: PoseFeatures, cfg: FallConfig) -> tuple[str, List[str]]:
    """Static posture of one frame. Returns (posture, reasons). WALKING is decided later from motion."""
    reasons: List[str] = []
    t, leg, thigh = f.torso_angle, f.leg_angle, f.thigh_angle

    if t is not None:
        legs_flat = leg is not None and leg >= cfg.lying_leg_angle
        if t >= cfg.lying_torso_angle and (legs_flat or (leg is None and f.aspect >= 1.0 and not f.truncated)):
            reasons.append(f"torso {t:.0f} deg from vertical" + (f", legs {leg:.0f} deg" if leg is not None else ""))
            return LYING, reasons
        if t >= cfg.lying_torso_angle and f.aspect >= cfg.lying_aspect_ratio and not f.truncated and (leg is None or leg >= 30):
            reasons.append(f"torso {t:.0f} deg, wide body box ({f.aspect:.2f})")
            return LYING, reasons
        if t >= cfg.bending_torso_angle and leg is not None and leg < 35:
            reasons.append(f"torso tilted {t:.0f} deg with legs vertical")
            return BENDING, reasons
        if t <= cfg.bending_torso_angle + 10:
            # Seated on the floor: upright torso, legs stretched out with the ankles level with the hips.
            if (f.ankle is not None and f.hip is not None and f.torso_length and thigh is not None and thigh >= 60
                    and f.ankle[1] - f.hip[1] < 0.35 * f.torso_length
                    and abs(f.ankle[0] - f.hip[0]) > 0.6 * f.torso_length):
                reasons.append("upright torso with legs extended at hip level (seated on the floor)")
                return ON_FLOOR, reasons
            if thigh is not None and thigh >= cfg.sitting_thigh_angle:
                reasons.append(f"upright torso, thighs {thigh:.0f} deg from vertical")
                return SITTING, reasons
            if t <= cfg.upright_torso_angle and (leg is None or leg < 30):
                return STANDING, reasons
            if leg is not None and leg >= 30:
                reasons.append(f"legs bent {leg:.0f} deg")
                return SITTING, reasons
            return STANDING, reasons
        if f.aspect >= cfg.lying_aspect_ratio + 0.2 and not f.truncated:
            reasons.append(f"torso {t:.0f} deg, wide body box ({f.aspect:.2f})")
            return LYING, reasons
        return UNKNOWN, reasons

    # Torso keypoints hidden: fall back to the bounding-box shape (never used to *trigger* a fall).
    if f.aspect >= cfg.lying_aspect_ratio + 0.25 and not f.truncated:
        reasons.append(f"wide body box ({f.aspect:.2f}), torso keypoints hidden")
        return LYING, reasons
    if f.aspect <= 0.55 and f.body_angle is not None and f.body_angle < 25:
        return STANDING, reasons
    return UNKNOWN, reasons


def is_down(posture: str) -> bool:
    return posture in DOWN


# ----------------------------------------------------------------------------- temporal monitor

@dataclass
class Sample:
    t: float
    posture: str
    features: PoseFeatures
    hip_y: Optional[float]
    reasons: List[str]


def _clamp01(v: float) -> float:
    return max(0.0, min(1.0, v))


@dataclass
class Verification:
    track_id: int
    start_t: float                 # first down sample
    upright_t: float               # last upright sample before the fall
    fall_score: float
    hip_drop: float                # standing heights
    height_ratio: float            # body box height / standing height after the fall
    rotation: float                # degrees of torso rotation
    posture_before: str
    trigger_reasons: List[str]
    window: float
    interval: float
    max_extension_factor: float = 3.0
    down_samples: int = 0
    down_weight: float = 0.0
    observed: int = 0
    recovery_since: Optional[float] = None
    last_seen_t: float = 0.0
    quality_sum: float = 0.0
    best_frame_quality: float = -1.0
    best_frame_t: Optional[float] = None
    status: str = "VERIFYING"      # VERIFYING | CONFIRMED | CANCELLED | INCONCLUSIVE
    end_t: Optional[float] = None
    end_reason: str = ""
    posture_after: str = LYING

    def elapsed(self, now: float) -> float:
        return max(0.0, now - self.start_t)

    def observed_down_seconds(self) -> float:
        return self.down_samples * self.interval

    def evidence(self) -> float:
        """Observed down-time relative to the verification window (1.0 = a full window of observations)."""
        return min(1.0, self.observed_down_seconds() / self.window)

    def down_ratio(self) -> float:
        return self.down_weight / self.observed if self.observed else 0.0

    def pose_quality(self) -> float:
        return self.quality_sum / self.observed if self.observed else 0.0

    def confidence(self) -> float:
        """Explainable blend: fall transition 35%, share of time down 35%, observed evidence 15%, keypoint quality 15%."""
        c = 0.35 * self.fall_score + 0.35 * self.down_ratio() + 0.15 * self.evidence() + 0.15 * self.pose_quality()
        return round(min(0.99, max(0.0, c)), 3)


class TrackState:
    """Posture history and fall state for one tracked person."""

    def __init__(self, track_id: int, cfg: FallConfig, sample_interval: float):
        self.id = track_id
        self.cfg = cfg
        self.interval = sample_interval
        self.history: Deque[Sample] = deque(maxlen=max(10, int(8 / sample_interval)))
        self.standing_heights: Deque[float] = deque(maxlen=40)
        self.verification: Optional[Verification] = None
        self.finished: List[Verification] = []
        self.last_posture = UNKNOWN

    @property
    def standing_height(self) -> Optional[float]:
        return median(self.standing_heights) if len(self.standing_heights) >= 2 else None

    def update(self, t: float, f: PoseFeatures) -> Sample:
        posture, reasons = classify_posture(f, self.cfg)
        hip_y = f.hip[1] if f.hip else None

        # Walking = standing with sustained horizontal hip motion.
        if posture == STANDING and f.hip and self.history:
            prev = next((s for s in reversed(self.history) if s.features.hip and t - s.t >= 0.4), None)
            if prev and t - prev.t <= 1.5:
                scale = self.standing_height or f.height
                if abs(f.hip[0] - prev.features.hip[0]) / scale / (t - prev.t) >= self.cfg.walking_speed:
                    posture = WALKING

        if posture in (STANDING, WALKING) and not f.truncated and f.torso_angle is not None \
                and f.torso_angle <= self.cfg.upright_torso_angle:
            self.standing_heights.append(f.height)

        sample = Sample(t, posture, f, hip_y, reasons)
        if self.verification is None:
            self._check_trigger(sample)
        else:
            self._verify(sample)
        self.history.append(sample)
        self.last_posture = posture
        return sample

    # -- potential fall detection -------------------------------------------------
    def _check_trigger(self, s: Sample) -> None:
        f, cfg = s.features, self.cfg
        if not is_down(s.posture) or f.torso_angle is None or f.visible < cfg.min_trigger_keypoints or f.truncated:
            return
        upright = [h for h in self.history if h.posture in UPRIGHT and s.t - h.t <= cfg.fall_window_seconds]
        sitting = [h for h in self.history if h.posture == SITTING and s.t - h.t <= 1.0]
        if not upright and not sitting:
            return  # no upright pose just before: deliberate lying down, or already on the floor
        standing_h = self.standing_height
        best = None
        for ref in upright + sitting:
            dt = max(self.interval, s.t - ref.t)
            scale = standing_h or ref.features.height
            drop = max(0.0, (s.hip_y - ref.hip_y) / scale) if s.hip_y is not None and ref.hip_y is not None else 0.0
            ratio = f.height / scale
            rotation = max(0.0, (f.torso_angle or 0) - (ref.features.torso_angle or 0))
            # Fall-likeness of the transition: quick, large drop, body box collapses, torso rotates.
            speed = _clamp01((cfg.fall_window_seconds + 0.5 - dt) / cfg.fall_window_seconds)
            score = (0.30 * speed + 0.25 * _clamp01(drop / 0.40) + 0.25 * _clamp01((1.0 - ratio) / 0.6)
                     + 0.20 * _clamp01((rotation - 15) / 50))
            if ref.posture == SITTING:
                # From a seat, only a violent transition counts: deliberately lying back rotates the torso slowly.
                earlier = next((h for h in self.history if s.t - h.t <= 1.0 + 1e-6 and h.features.torso_angle is not None), None)
                if earlier is None or (f.torso_angle - earlier.features.torso_angle) < cfg.chair_fall_rotation:
                    continue
                score -= 0.10
            significant = drop >= cfg.min_hip_drop or ratio <= cfg.max_height_ratio
            if significant and (best is None or score > best[0]):
                best = (score, drop, ratio, rotation, ref, dt)
        if best is None or best[0] < cfg.trigger_score:
            return
        score, drop, ratio, rotation, ref, dt = best
        reasons = [
            f"{ref.posture.lower()} -> {s.posture.lower().replace('_', ' ')} within {dt:.1f} s",
            f"hip dropped {drop:.2f} body-heights",
            f"body height shrank to {ratio:.0%} of standing height",
            f"torso rotated {rotation:.0f} deg",
        ] + s.reasons
        self.verification = Verification(
            track_id=self.id, start_t=s.t, upright_t=ref.t, fall_score=round(score, 3), hip_drop=round(drop, 3),
            height_ratio=round(ratio, 3), rotation=round(rotation, 1), posture_before=ref.posture,
            trigger_reasons=reasons, window=cfg.verification_seconds, interval=self.interval,
            max_extension_factor=cfg.max_extension_factor, last_seen_t=s.t)
        self._accumulate(s)

    # -- verification -----------------------------------------------------------------
    def _accumulate(self, s: Sample) -> None:
        v = self.verification
        v.observed += 1
        v.last_seen_t = s.t
        v.quality_sum += s.features.quality
        if is_down(s.posture):
            v.down_samples += 1
            v.down_weight += 1.0
            v.posture_after = s.posture if v.posture_after not in DOWN or s.posture == LYING else v.posture_after
            if s.features.quality > v.best_frame_quality:
                v.best_frame_quality = s.features.quality
                v.best_frame_t = s.t
        elif s.posture in (SITTING, UNKNOWN):
            v.down_weight += 0.5  # not clearly up; often a partially hidden person on the ground

    def _verify(self, s: Sample) -> None:
        v = self.verification
        self._accumulate(s)
        if s.posture in (STANDING, WALKING):
            v.recovery_since = v.recovery_since if v.recovery_since is not None else s.t
            if s.t - v.recovery_since >= self.cfg.recovery_seconds - 1e-6:
                self._finish("CANCELLED", s.t, f"person got up {v.recovery_since - v.start_t:.1f} s after the potential fall")
                return
        else:
            v.recovery_since = None
        self.evaluate(s.t)

    def evaluate(self, now: float, video_ended: bool = False) -> None:
        """Decides the verification outcome once enough time has passed (or the video ended)."""
        v = self.verification
        if v is None:
            return
        cfg = self.cfg
        elapsed = v.elapsed(now)
        enough_evidence = v.observed_down_seconds() >= cfg.min_observed_fraction * v.window
        if elapsed + 1e-6 >= v.window:
            ratio = v.down_ratio()
            if ratio < cfg.cancel_down_ratio and v.observed >= 3:
                self._finish("CANCELLED", now, f"down in only {ratio:.0%} of observations")
                return
            if enough_evidence and ratio >= cfg.confirm_down_ratio:
                self._finish("CONFIRMED", now, f"remained down for {elapsed:.1f} s "
                                               f"(observed down {v.observed_down_seconds():.1f} s, {ratio:.0%} of observations)")
                return
            if elapsed + 1e-6 >= v.window * cfg.max_extension_factor:
                why = "person not visible enough" if not enough_evidence else f"mixed evidence ({ratio:.0%} of observations down)"
                self._finish("INCONCLUSIVE", now, f"{why} after {elapsed:.1f} s of verification")
                return
        if video_ended:
            if elapsed + 1e-6 < v.window:
                why = f"video ended {elapsed:.1f} s after the potential fall; {v.window:.0f} s of footage needed"
            else:
                why = f"video ended before enough evidence was collected (observed down {v.observed_down_seconds():.1f} s)"
            self._finish("INCONCLUSIVE", now, why)

    def _finish(self, status: str, now: float, reason: str) -> None:
        v = self.verification
        v.status, v.end_t, v.end_reason = status, now, reason
        self.finished.append(v)
        self.verification = None
