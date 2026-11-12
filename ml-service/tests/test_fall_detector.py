"""Unit tests for posture classification and temporal fall verification using synthetic YOLO keypoints.

Run from ml-service/:  py -m unittest discover -s tests -v
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fall_detector import (BENDING, LYING, SITTING, STANDING, FallConfig, TrackState,  # noqa: E402
                           classify_posture, extract_features)

FRAME = (1280, 720)
DT = 1 / 6  # six analysed frames per second, the service default


def pose(points, bbox):
    """points: dict index -> (x, y); every other keypoint is invisible."""
    kps = [[0.0, 0.0, 0.0] for _ in range(17)]
    for i, (x, y) in points.items():
        kps[i] = [x, y, 0.9]
    return kps, bbox


STAND = pose({0: (500, 320), 5: (480, 360), 6: (520, 360), 11: (490, 450), 12: (510, 450), 13: (490, 525),
              14: (510, 525), 15: (490, 600), 16: (510, 600)}, [465, 300, 535, 600])
LIE = pose({0: (350, 580), 5: (400, 570), 6: (400, 590), 11: (490, 572), 12: (490, 588), 13: (570, 575),
            14: (570, 590), 15: (650, 578), 16: (650, 592)}, [340, 555, 665, 605])
SIT = pose({0: (500, 390), 5: (480, 420), 6: (520, 420), 11: (490, 500), 12: (510, 500), 13: (560, 505),
            14: (575, 505), 15: (560, 590), 16: (575, 590)}, [470, 380, 585, 600])
BEND = pose({0: (600, 440), 5: (560, 420), 6: (575, 430), 11: (490, 450), 12: (510, 450), 13: (490, 525),
             14: (510, 525), 15: (490, 600), 16: (510, 600)}, [480, 400, 620, 600])


def blend(a, b, w):
    """Linear interpolation between two synthetic poses (w = 0 -> a, w = 1 -> b)."""
    kps = [[ka[0] + (kb[0] - ka[0]) * w, ka[1] + (kb[1] - ka[1]) * w, max(ka[2], kb[2])] for ka, kb in zip(a[0], b[0])]
    box = [ba + (bb - ba) * w for ba, bb in zip(a[1], b[1])]
    return kps, box


class Sequence:
    def __init__(self, cfg=None):
        self.cfg = cfg or FallConfig()
        self.track = TrackState(1, self.cfg, DT)
        self.t = 0.0

    def run(self, p, seconds):
        for _ in range(round(seconds / DT)):
            self.track.update(self.t, extract_features(p[0], p[1], self.cfg, FRAME))
            self.t += DT
        return self

    def transition(self, a, b, seconds):
        n = round(seconds / DT)
        for k in range(1, n + 1):
            self.run(blend(a, b, k / n), DT)
        return self

    def gap(self, seconds):
        """Person not detected (occlusion): only the clock advances."""
        for _ in range(round(seconds / DT)):
            self.track.evaluate(self.t)
            self.t += DT
        return self

    def end(self):
        self.track.evaluate(self.t, video_ended=True)
        return self

    @property
    def statuses(self):
        return [v.status for v in self.track.finished]


class PostureTests(unittest.TestCase):
    def classify(self, p):
        cfg = FallConfig()
        return classify_posture(extract_features(p[0], p[1], cfg, FRAME), cfg)[0]

    def test_static_postures(self):
        self.assertEqual(self.classify(STAND), STANDING)
        self.assertEqual(self.classify(LIE), LYING)
        self.assertEqual(self.classify(SIT), SITTING)
        self.assertEqual(self.classify(BEND), BENDING)

    def test_wide_box_alone_is_lying_but_cannot_trigger(self):
        kps = [[0.0, 0.0, 0.0] for _ in range(17)]
        seq = Sequence().run(STAND, 2)
        seq.run((kps, [300, 560, 700, 620]), 6)
        self.assertEqual(seq.track.last_posture, LYING)
        self.assertEqual(seq.statuses, [])  # no torso keypoints -> never starts a verification


class VerificationTests(unittest.TestCase):
    def test_fall_that_stays_down_is_confirmed(self):
        seq = Sequence().run(STAND, 3).run(LIE, 6)
        self.assertEqual(seq.statuses, ["CONFIRMED"])
        v = seq.track.finished[0]
        self.assertGreaterEqual(v.confidence(), 0.70)
        self.assertEqual(v.posture_before, STANDING)

    def test_person_who_gets_up_is_cancelled(self):
        seq = Sequence().run(STAND, 3).run(LIE, 2).run(STAND, 2)
        self.assertEqual(seq.statuses, ["CANCELLED"])

    def test_single_noisy_lying_frame_never_confirms(self):
        seq = Sequence().run(STAND, 3).run(LIE, DT).run(STAND, 8).end()
        self.assertNotIn("CONFIRMED", seq.statuses)

    def test_sitting_down_is_not_a_fall(self):
        seq = Sequence().run(STAND, 3).run(SIT, 8).end()
        self.assertEqual(seq.statuses, [])

    def test_bending_is_not_a_fall(self):
        seq = Sequence().run(STAND, 2).run(BEND, 4).run(STAND, 2).end()
        self.assertEqual(seq.statuses, [])

    def test_deliberate_lying_down_after_sitting_is_not_a_fall(self):
        seq = Sequence().run(STAND, 3).run(SIT, 4).transition(SIT, LIE, 2.5).run(LIE, 8).end()
        self.assertEqual(seq.statuses, [])

    def test_violent_fall_from_a_chair_is_detected(self):
        seq = Sequence().run(STAND, 3).run(SIT, 4).transition(SIT, LIE, 0.34).run(LIE, 6)
        self.assertEqual(seq.statuses, ["CONFIRMED"])

    def test_gradual_fall_from_standing_is_detected(self):
        seq = Sequence().run(STAND, 3).transition(STAND, LIE, 1.0).run(LIE, 6)
        self.assertEqual(seq.statuses, ["CONFIRMED"])

    def test_video_ending_shortly_after_fall_is_inconclusive(self):
        seq = Sequence().run(STAND, 3).run(LIE, 2).end()
        self.assertEqual(seq.statuses, ["INCONCLUSIVE"])
        self.assertIn("video ended", seq.track.finished[0].end_reason)

    def test_brief_occlusion_is_tolerated(self):
        seq = Sequence().run(STAND, 3).run(LIE, 1.5).gap(3).run(LIE, 2)
        self.assertEqual(seq.statuses, ["CONFIRMED"])

    def test_person_not_visible_after_fall_is_inconclusive(self):
        seq = Sequence().run(STAND, 3).run(LIE, 1).gap(16)
        self.assertEqual(seq.statuses, ["INCONCLUSIVE"])

    def test_verification_window_is_configurable(self):
        seq = Sequence(FallConfig(verification_seconds=10)).run(STAND, 3).run(LIE, 6)
        self.assertEqual(seq.statuses, [])  # still verifying after 6 s
        seq.run(LIE, 5)
        self.assertEqual(seq.statuses, ["CONFIRMED"])


if __name__ == "__main__":
    unittest.main()
