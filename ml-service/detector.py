"""Video analysis pipeline: OpenCV frame sampling -> YOLO Pose -> tracking -> posture -> temporal fall verification."""
from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, Dict, List, Optional

import cv2
import numpy as np

from config import settings
from fall_detector import FallConfig, PoseFeatures, TrackState, Verification, extract_features
from tracker import SimpleTracker

log = logging.getLogger("ml.detector")

SKELETON = [(5, 6), (5, 7), (7, 9), (6, 8), (8, 10), (5, 11), (6, 12), (11, 12), (11, 13), (13, 15), (12, 14), (14, 16),
            (0, 5), (0, 6)]


class AnalysisCancelled(Exception):
    pass


class VideoValidationError(ValueError):
    pass


class ModelManager:
    """Loads YOLO Pose once per process and serialises inference (Ultralytics predictors are not thread-safe)."""

    def __init__(self, path: str):
        self.path = path
        self.model = None
        self.error: Optional[str] = None
        self.load_ms: Optional[int] = None
        self.device = "cpu"
        self.inferences = 0
        self.inference_ms_total = 0.0
        self._lock = threading.Lock()

    @property
    def loaded(self) -> bool:
        return self.model is not None

    @property
    def name(self) -> str:
        return Path(self.path).name

    def load(self) -> None:
        if self.model is not None:
            return
        start = time.perf_counter()
        try:
            from ultralytics import YOLO
            import torch
            self.device = "cuda:0" if torch.cuda.is_available() else "cpu"
            # PyTorch 2.6+ defaults ``torch.load`` to weights_only=True, while
            # older Ultralytics releases load the trusted, bundled .pt checkpoint
            # as a complete model object. Limit this compatibility override to
            # the local checkpoint load and restore torch immediately afterwards.
            original_torch_load = torch.load

            def load_trusted_checkpoint(*args, **kwargs):
                kwargs.setdefault("weights_only", False)
                return original_torch_load(*args, **kwargs)

            torch.load = load_trusted_checkpoint
            try:
                model = YOLO(self.path)
            finally:
                torch.load = original_torch_load
            if getattr(model, "task", "pose") != "pose":
                raise RuntimeError(f"{self.name} is not a pose model")
            # Warm-up so the first real request is not slow.
            model(np.zeros((640, 640, 3), dtype=np.uint8), imgsz=640, verbose=False, device=self.device)
            self.model = model
            self.error = None
            self.load_ms = int((time.perf_counter() - start) * 1000)
            log.info("Loaded %s on %s in %d ms", self.name, self.device, self.load_ms)
        except Exception as exc:  # pragma: no cover - depends on environment
            self.error = f"{type(exc).__name__}: {exc}"
            log.exception("Could not load YOLO model %s", self.path)

    @staticmethod
    def inference_size_for(width: int, height: int) -> int:
        if settings.inference_size > 0:
            return settings.inference_size
        longest = max(width, height)
        return int(min(1280, max(640, round(longest / 32) * 32)))

    def detect(self, frame, imgsz: int = 640) -> List[dict]:
        if self.model is None:
            raise RuntimeError("YOLO model is not loaded" + (f" ({self.error})" if self.error else ""))
        start = time.perf_counter()
        with self._lock:
            result = self.model(frame, imgsz=imgsz, conf=settings.person_confidence,
                                verbose=False, device=self.device)[0]
        self.inferences += 1
        self.inference_ms_total += (time.perf_counter() - start) * 1000
        people = []
        if result.boxes is None or result.keypoints is None or len(result.boxes) == 0:
            return people
        boxes = result.boxes.xyxy.cpu().numpy().tolist()
        confs = result.boxes.conf.cpu().numpy().tolist()
        kps = result.keypoints.data.cpu().numpy().tolist()
        for box, conf, kp in zip(boxes, confs, kps):
            people.append({"bbox": box, "confidence": float(conf), "keypoints": kp})
        return people

    def status(self) -> dict:
        return {
            "loaded": self.loaded,
            "model": self.name,
            "path": str(Path(self.path).name),
            "task": "pose",
            "keypoints": 17,
            "device": self.device,
            "inferenceSize": settings.inference_size or "adaptive (640-1280)",
            "loadTimeMs": self.load_ms,
            "inferences": self.inferences,
            "avgInferenceMs": round(self.inference_ms_total / self.inferences, 1) if self.inferences else None,
            "error": self.error,
        }


model_manager = ModelManager(settings.model_path)


@dataclass
class AnalysisOptions:
    confidence_threshold: float = settings.default_confidence_threshold
    verification_seconds: float = settings.default_verification_seconds
    analysis_fps: float = settings.analysis_fps


def probe_video(path: str) -> dict:
    """Validates that OpenCV can decode the file and returns its metadata."""
    cap = cv2.VideoCapture(str(path))
    try:
        if not cap.isOpened():
            raise VideoValidationError("The file could not be opened as a video.")
        fps = cap.get(cv2.CAP_PROP_FPS) or 0.0
        if not (1.0 <= fps <= 240.0):
            fps = 25.0
        frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        width, height = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        ok, frame = cap.read()
        if not ok or frame is None:
            raise VideoValidationError("The video contains no decodable frames.")
        duration = frames / fps if frames > 0 else None
        if duration is not None and duration > settings.max_video_seconds:
            raise VideoValidationError(f"Video is {duration:.0f} s long; the maximum is {settings.max_video_seconds} s.")
        return {"fps": round(fps, 3), "totalFrames": frames if frames > 0 else None, "width": width or frame.shape[1],
                "height": height or frame.shape[0], "durationSeconds": round(duration, 2) if duration else None}
    finally:
        cap.release()


def annotate(frame, person: Optional[dict], label: str, color=(60, 60, 220)):
    img = frame.copy()
    if person:
        kps = person["keypoints"]
        for a, b in SKELETON:
            if kps[a][2] > 0.35 and kps[b][2] > 0.35:
                cv2.line(img, (int(kps[a][0]), int(kps[a][1])), (int(kps[b][0]), int(kps[b][1])), (255, 200, 60), 2, cv2.LINE_AA)
        for x, y, c in kps:
            if c > 0.35:
                cv2.circle(img, (int(x), int(y)), 3, (255, 255, 255), -1, cv2.LINE_AA)
        x1, y1, x2, y2 = [int(v) for v in person["bbox"]]
        cv2.rectangle(img, (x1, y1), (x2, y2), color, 2)
    scale = max(0.5, img.shape[1] / 1280)
    (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.6 * scale, 2)
    cv2.rectangle(img, (10, 10), (20 + tw, 22 + th), (40, 30, 20), -1)
    cv2.putText(img, label, (15, 15 + th), cv2.FONT_HERSHEY_SIMPLEX, 0.6 * scale, (255, 255, 255), 2, cv2.LINE_AA)
    return img


def _verification_dict(v: Verification, threshold: float) -> dict:
    return {
        "trackId": v.track_id,
        "status": v.status,
        "fallStartSeconds": round(v.start_t, 2),
        "lastUprightSeconds": round(v.upright_t, 2),
        "endSeconds": round(v.end_t, 2) if v.end_t is not None else None,
        "elapsedSeconds": round(v.elapsed(v.end_t if v.end_t is not None else v.last_seen_t), 2),
        "windowSeconds": v.window,
        "observedDownSeconds": round(v.observed_down_seconds(), 2),
        "fallScore": v.fall_score,
        "hipDrop": v.hip_drop,
        "heightRatio": v.height_ratio,
        "torsoRotation": v.rotation,
        "downRatio": round(v.down_ratio(), 3),
        "evidence": round(v.evidence(), 3),
        "poseQuality": round(v.pose_quality(), 3),
        "confidence": v.confidence(),
        "confidenceThreshold": threshold,
        "postureBefore": v.posture_before,
        "postureAfter": v.posture_after,
        "reasons": v.trigger_reasons + ([v.end_reason] if v.end_reason else []),
    }


def analyze_video(path: str, options: AnalysisOptions, video_name: str = "video",
                  progress: Optional[Callable[[dict], None]] = None,
                  cancel: Optional[threading.Event] = None,
                  frame_path: Optional[Path] = None) -> dict:
    """Runs the full detection pipeline on one video file and returns a structured, explainable result."""
    started = time.perf_counter()
    started_wall = datetime.now(timezone.utc)
    meta = probe_video(path)
    fps = meta["fps"]
    sample_every = max(1, round(fps / max(1.0, options.analysis_fps)))
    interval = sample_every / fps
    cfg = FallConfig(verification_seconds=float(options.verification_seconds))
    total_samples = (meta["totalFrames"] // sample_every) if meta["totalFrames"] else None
    imgsz = model_manager.inference_size_for(meta["width"], meta["height"])

    tracker = SimpleTracker(max_age=3.0)
    tracks: Dict[int, TrackState] = {}
    finished: List[Verification] = []
    events: List[dict] = []
    evidence: Dict[int, tuple] = {}   # verification start_t key -> (quality, frame, person, t)
    max_people, frames_analyzed, people_seen, confident_streak = 0, 0, False, 0
    last_t = 0.0

    def emit(stage: str, t: float, extra: Optional[dict] = None):
        if progress is None:
            return
        active = [tr.verification for tr in tracks.values() if tr.verification]
        payload = {
            "stage": stage,
            "framesAnalyzed": frames_analyzed,
            "framesTotal": total_samples,
            "videoSeconds": round(t, 2),
            "durationSeconds": meta["durationSeconds"],
            "percent": round(min(99.0, 100.0 * t / meta["durationSeconds"]), 1) if meta["durationSeconds"] else None,
            "peopleInFrame": extra.get("people", 0) if extra else 0,
            "personDetected": people_seen,
            "postures": extra.get("postures", []) if extra else [],
            "verification": {
                "trackId": active[0].track_id,
                "elapsedSeconds": round(active[0].elapsed(t), 2),
                "windowSeconds": active[0].window,
                "observedDownSeconds": round(active[0].observed_down_seconds(), 2),
                "downRatio": round(active[0].down_ratio(), 2),
            } if active else None,
            "events": events[-10:],
        }
        progress(payload)

    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise VideoValidationError("The file could not be opened as a video.")
    emit("EXTRACTING_FRAMES", 0.0)
    frame_index = -1
    confirmed: Optional[Verification] = None
    try:
        while True:
            if cancel is not None and cancel.is_set():
                raise AnalysisCancelled()
            frame_index += 1
            # grab() skips decoding of frames that are not analysed
            if frame_index % sample_every:
                if not cap.grab():
                    break
                continue
            ok, frame = cap.read()
            if not ok or frame is None:
                break
            t = frame_index / fps
            last_t = t
            people = model_manager.detect(frame, imgsz)
            frames_analyzed += 1
            # "Person detected" requires a confident box with a usable skeleton, which filters
            # low-confidence hallucinations on textures. All boxes are still tracked.
            confident = [p for p in people if p["confidence"] >= 0.5 and sum(k[2] >= 0.35 for k in p["keypoints"]) >= 8]
            confident_streak = confident_streak + 1 if confident else 0
            if confident_streak >= 3:
                max_people = max(max_people, len(confident))
            if confident_streak >= 3 and not people_seen:
                people_seen = True
                events.append({"t": round(t, 2), "type": "PERSON_DETECTED", "message": f"Person detected at {t:.1f} s"})

            verifying_ids = {tid for tid, tr in tracks.items() if tr.verification}
            pairs = tracker.update([p["bbox"] for p in people], t, keep_alive=verifying_ids)
            seen = set()
            postures = []
            for det_index, tid in pairs:
                person = people[det_index]
                state = tracks.setdefault(tid, TrackState(tid, cfg, interval))
                had_verification = state.verification is not None
                features: PoseFeatures = extract_features(person["keypoints"], person["bbox"], cfg, (frame.shape[1], frame.shape[0]))
                sample = state.update(t, features)
                seen.add(tid)
                postures.append({"trackId": tid, "posture": sample.posture})
                v = state.verification
                if v is not None and not had_verification:
                    events.append({"t": round(t, 2), "type": "POTENTIAL_FALL",
                                   "message": f"Potential fall (person {tid}) at {v.start_t:.1f} s: " + "; ".join(v.trigger_reasons[:3])})
                    evidence[id(v)] = (-1.0, None, None, t)
                if v is not None and v.best_frame_t == t:
                    q = features.quality
                    if q > evidence.get(id(v), (-1.0,))[0]:
                        evidence[id(v)] = (q, frame.copy(), person, t)
                for done in state.finished:
                    if done not in finished:
                        finished.append(done)
                        events.append({"t": round(t, 2), "type": f"VERIFICATION_{done.status}",
                                       "message": f"Verification {done.status.lower()} (person {tid}): {done.end_reason}"})
            # Tracks under verification that were not visible in this frame still need their window evaluated.
            for tid, state in tracks.items():
                if tid not in seen and state.verification is not None:
                    state.evaluate(t)
                    for done in state.finished:
                        if done not in finished:
                            finished.append(done)
                            events.append({"t": round(t, 2), "type": f"VERIFICATION_{done.status}",
                                           "message": f"Verification {done.status.lower()} (person {tid}): {done.end_reason}"})
            confirmed = next((v for v in finished if v.status == "CONFIRMED"), None)
            if confirmed is not None:
                break  # first confirmed fall ends the analysis
            stage = "VERIFYING_FALL" if any(tr.verification for tr in tracks.values()) else (
                "ANALYZING_POSTURE" if people else "DETECTING_PERSON")
            if frames_analyzed % 4 == 0 or stage == "VERIFYING_FALL":
                emit(stage, t, {"people": len(people), "postures": postures})
    finally:
        cap.release()

    if confirmed is None:
        # Never confirm because the video ended: open verifications become INCONCLUSIVE.
        for tid, state in tracks.items():
            if state.verification is not None:
                state.evaluate(last_t, video_ended=True)
                for done in state.finished:
                    if done not in finished:
                        finished.append(done)
                        events.append({"t": round(last_t, 2), "type": f"VERIFICATION_{done.status}",
                                       "message": f"Verification {done.status.lower()} (person {tid}): {done.end_reason}"})

    threshold = float(options.confidence_threshold)
    chosen: Optional[Verification] = confirmed
    if chosen is None:
        inconclusive = [v for v in finished if v.status == "INCONCLUSIVE"]
        cancelled = [v for v in finished if v.status == "CANCELLED"]
        if inconclusive:
            chosen = max(inconclusive, key=lambda v: v.confidence())
        elif cancelled:
            chosen = cancelled[-1]

    result = {
        "status": "COMPLETED",
        "video": {"name": video_name, **meta},
        "framesAnalyzed": frames_analyzed,
        "analysisFps": round(fps / sample_every, 2),
        "personDetected": people_seen,
        "maxPeopleDetected": max_people,
        "potentialFalls": len(finished),
        "verificationSeconds": cfg.verification_seconds,
        "confidenceThreshold": threshold,
        "model": model_manager.name,
        "inferenceSize": imgsz,
        "events": events,
    }

    if chosen is None:
        result.update(outcome="NO_FALL", fallDetected=False, verificationStatus="NOT_TRIGGERED", confidence=0.0,
                      message="No fall detected." if people_seen else "No person was detected in this video.")
    else:
        details = _verification_dict(chosen, threshold)
        conf = details["confidence"]
        if chosen.status == "CONFIRMED" and conf >= threshold:
            outcome, status, message = "FALL_CONFIRMED", "CONFIRMED", (
                f"Fall confirmed: fall at {chosen.start_t:.1f} s, person remained down for the {cfg.verification_seconds:.0f} s verification period.")
        elif chosen.status == "CONFIRMED":
            outcome, status, message = "INCONCLUSIVE", "BELOW_THRESHOLD", (
                f"Person stayed down after a potential fall at {chosen.start_t:.1f} s, but confidence {conf:.0%} is below the "
                f"{threshold:.0%} threshold. Caregiver review recommended.")
            details["reasons"].append(f"confidence {conf:.0%} below threshold {threshold:.0%}")
        elif chosen.status == "INCONCLUSIVE":
            outcome, status, message = "INCONCLUSIVE", "INCONCLUSIVE", (
                f"Potential fall at {chosen.start_t:.1f} s could not be verified: {chosen.end_reason}.")
        else:
            outcome, status, message = "NO_FALL", "CANCELLED", (
                f"Potential fall at {chosen.start_t:.1f} s was cancelled: {chosen.end_reason}.")
        observed_down = details["observedDownSeconds"]
        result.update(outcome=outcome, fallDetected=outcome == "FALL_CONFIRMED", verificationStatus=status,
                      confidence=conf, detectionTimestamp=chosen.start_t, confirmedAtSeconds=details["endSeconds"],
                      observedDownSeconds=observed_down, detectedAt=(started_wall + timedelta(
                          seconds=time.perf_counter() - started)).isoformat(), verification=details, message=message)
        ev = evidence.get(id(chosen))
        if frame_path is not None and ev and ev[1] is not None:
            label = f"{outcome.replace('_', ' ')} | t={ev[3]:.1f}s | person {chosen.track_id} | {conf:.0%}"
            color = (60, 60, 220) if outcome == "FALL_CONFIRMED" else (30, 150, 230)
            frame_path.parent.mkdir(parents=True, exist_ok=True)
            img = annotate(ev[1], ev[2], label, color)
            h, w = img.shape[:2]
            if max(h, w) > 1280:  # keep reports and API payloads small
                s = 1280 / max(h, w)
                img = cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
            cv2.imwrite(str(frame_path), img, [cv2.IMWRITE_JPEG_QUALITY, 88])
            result["frameCaptured"] = True
            result["frameVideoSeconds"] = round(ev[3], 2)
    result.setdefault("frameCaptured", False)
    result["processingMs"] = int((time.perf_counter() - started) * 1000)
    return result


if __name__ == "__main__":  # Local CLI: py detector.py <video> [verification_seconds]
    import json
    import sys

    logging.basicConfig(level=logging.INFO)
    if len(sys.argv) < 2:
        print("usage: py detector.py <video> [verification_seconds]")
        sys.exit(1)
    model_manager.load()
    opts = AnalysisOptions(verification_seconds=float(sys.argv[2]) if len(sys.argv) > 2 else settings.default_verification_seconds)
    out = Path(settings.storage_dir) / "cli" / "frame.jpg"
    res = analyze_video(sys.argv[1], opts, Path(sys.argv[1]).name, frame_path=out)
    print(json.dumps(res, indent=2))
