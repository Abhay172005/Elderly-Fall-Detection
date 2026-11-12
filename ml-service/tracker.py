"""Lightweight multi-person tracker for keeping identities across sampled frames.

A fall changes a person's box from tall to wide, so plain IoU matching loses the identity exactly when it matters.
Matching therefore combines IoU with the distance between box centres normalised by the person's size.
Each video job owns its own tracker, so concurrent jobs never share state.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Dict, List, Sequence, Tuple


def iou(a: Sequence[float], b: Sequence[float]) -> float:
    ix1, iy1, ix2, iy2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


@dataclass
class Track:
    id: int
    bbox: Sequence[float]
    last_t: float
    hits: int = 1
    size: float = field(default=0.0)

    def __post_init__(self):
        self.size = max(self.bbox[2] - self.bbox[0], self.bbox[3] - self.bbox[1])


class SimpleTracker:
    def __init__(self, max_age: float = 2.0):
        self.max_age = max_age
        self.tracks: Dict[int, Track] = {}
        self._next = 1

    def _score(self, track: Track, box: Sequence[float]) -> float:
        overlap = iou(track.bbox, box)
        tc = ((track.bbox[0] + track.bbox[2]) / 2, (track.bbox[1] + track.bbox[3]) / 2)
        bc = ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2)
        size = max(track.size, box[2] - box[0], box[3] - box[1], 1.0)
        proximity = 1.0 - min(1.0, math.dist(tc, bc) / (0.9 * size))
        if overlap < 0.05 and proximity <= 0.0:
            return 0.0
        return overlap + 0.6 * proximity

    def update(self, boxes: List[Sequence[float]], t: float, keep_alive: set | None = None) -> List[Tuple[int, int]]:
        """Matches detections to tracks. Returns (detection_index, track_id) pairs. ``keep_alive`` track ids are never expired."""
        keep_alive = keep_alive or set()
        candidates = []
        for ti, track in self.tracks.items():
            for di, box in enumerate(boxes):
                s = self._score(track, box)
                if s > 0.15:
                    candidates.append((s, ti, di))
        candidates.sort(reverse=True)
        used_tracks, used_dets, pairs = set(), set(), []
        for s, ti, di in candidates:
            if ti in used_tracks or di in used_dets:
                continue
            used_tracks.add(ti)
            used_dets.add(di)
            track = self.tracks[ti]
            track.bbox, track.last_t, track.hits = boxes[di], t, track.hits + 1
            track.size = 0.7 * track.size + 0.3 * max(boxes[di][2] - boxes[di][0], boxes[di][3] - boxes[di][1])
            pairs.append((di, ti))
        for di, box in enumerate(boxes):
            if di not in used_dets:
                tid = self._next
                self._next += 1
                self.tracks[tid] = Track(tid, box, t)
                pairs.append((di, tid))
        for tid in [k for k, tr in self.tracks.items() if t - tr.last_t > self.max_age and k not in keep_alive]:
            del self.tracks[tid]
        return pairs
