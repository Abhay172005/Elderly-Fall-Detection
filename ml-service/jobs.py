"""Asynchronous video-analysis jobs with real progress, cancellation and cleanup."""
from __future__ import annotations

import logging
import os
import threading
import time
import uuid
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Optional

from api_client import BackendError, build_payload, send_detection
from config import settings
from detector import AnalysisCancelled, AnalysisOptions, VideoValidationError, analyze_video

log = logging.getLogger("ml.jobs")

ACTIVE = {"QUEUED", "PROCESSING"}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class Job:
    id: str
    video_name: str
    video_path: Path
    params: dict
    status: str = "QUEUED"            # QUEUED | PROCESSING | COMPLETED | FAILED | CANCELLED
    stage: str = "QUEUED"             # fine-grained stage reported by the pipeline
    progress: dict = field(default_factory=dict)
    result: Optional[dict] = None
    error: Optional[str] = None
    incident: Optional[dict] = None
    incident_error: Optional[str] = None
    created_at: str = field(default_factory=_now)
    started_at: Optional[str] = None
    finished_at: Optional[str] = None
    finished_mono: Optional[float] = None
    cancel_event: threading.Event = field(default_factory=threading.Event)
    future: Optional[Future] = None

    @property
    def frame_path(self) -> Path:
        return settings.storage_dir / "jobs" / f"{self.id}.jpg"

    def snapshot(self) -> dict:
        return {
            "jobId": self.id,
            "status": self.status,
            "stage": self.stage,
            "videoName": self.video_name,
            "progress": self.progress,
            "result": self.result,
            "error": self.error,
            "incident": self.incident,
            "incidentError": self.incident_error,
            "frameAvailable": self.frame_path.is_file(),
            "settings": {
                "confidenceThreshold": self.params.get("confidence_threshold"),
                "verificationSeconds": self.params.get("verification_seconds"),
                "recordInconclusive": self.params.get("record_inconclusive"),
                "residentName": self.params.get("resident_name"),
                "cameraName": self.params.get("camera_name"),
            },
            "createdAt": self.created_at,
            "startedAt": self.started_at,
            "finishedAt": self.finished_at,
        }


class JobManager:
    def __init__(self):
        self._jobs: Dict[str, Job] = {}
        self._lock = threading.Lock()
        self._executor = ThreadPoolExecutor(max_workers=max(1, settings.max_concurrent_jobs), thread_name_prefix="video-job")
        (settings.storage_dir / "jobs").mkdir(parents=True, exist_ok=True)
        (settings.storage_dir / "uploads").mkdir(parents=True, exist_ok=True)

    def counts(self) -> dict:
        with self._lock:
            return {
                "active": sum(1 for j in self._jobs.values() if j.status == "PROCESSING"),
                "queued": sum(1 for j in self._jobs.values() if j.status == "QUEUED"),
            }

    def submit(self, video_path: Path, video_name: str, params: dict) -> Job:
        self._purge()
        job = Job(id=uuid.uuid4().hex[:20], video_name=video_name, video_path=video_path, params=params)
        with self._lock:
            self._jobs[job.id] = job
        job.future = self._executor.submit(self._run, job)
        return job

    def get(self, job_id: str) -> Optional[Job]:
        with self._lock:
            return self._jobs.get(job_id)

    def cancel(self, job_id: str) -> Optional[Job]:
        job = self.get(job_id)
        if job is None:
            return None
        if job.status in ACTIVE:
            job.cancel_event.set()
            if job.status == "QUEUED" and job.future is not None and job.future.cancel():
                self._finish(job, "CANCELLED", "CANCELLED")
                self._remove_video(job)
        return job

    # -------------------------------------------------------------- worker
    def _run(self, job: Job) -> None:
        if job.cancel_event.is_set():
            self._finish(job, "CANCELLED", "CANCELLED")
            self._remove_video(job)
            return
        job.status, job.stage, job.started_at = "PROCESSING", "VALIDATING_VIDEO", _now()
        try:
            options = AnalysisOptions(confidence_threshold=float(job.params["confidence_threshold"]),
                                      verification_seconds=float(job.params["verification_seconds"]))

            def on_progress(p: dict) -> None:
                job.stage = p.get("stage", job.stage)
                job.progress = p

            result = analyze_video(str(job.video_path), options, job.video_name, progress=on_progress,
                                   cancel=job.cancel_event, frame_path=job.frame_path)
            job.result = result
            self._report(job, result)
            self._finish(job, "COMPLETED", "COMPLETED")
            log.info("Job %s completed: %s (%.0f%%)", job.id, result["outcome"], result.get("confidence", 0) * 100)
        except AnalysisCancelled:
            self._finish(job, "CANCELLED", "CANCELLED")
            log.info("Job %s cancelled", job.id)
        except VideoValidationError as exc:
            job.error = str(exc)
            self._finish(job, "FAILED", "FAILED")
        except Exception as exc:  # unexpected failure: report it, never crash the worker
            log.exception("Job %s failed", job.id)
            job.error = f"Video analysis failed ({type(exc).__name__})."
            self._finish(job, "FAILED", "FAILED")
        finally:
            self._remove_video(job)

    def _report(self, job: Job, result: dict) -> None:
        """Sends confirmed (and, if enabled, inconclusive) detections to the backend."""
        outcome = result.get("outcome")
        wanted = outcome == "FALL_CONFIRMED" or (outcome == "INCONCLUSIVE" and job.params.get("record_inconclusive"))
        if not job.params.get("report_incident") or not wanted:
            return
        job.stage = "SAVING_INCIDENT"
        try:
            payload = build_payload(job.id, result, job.params, job.frame_path)
            job.incident = send_detection(payload)
        except BackendError as exc:
            job.incident_error = str(exc)
            log.error("Job %s: %s", job.id, exc)

    def retry_report(self, job: Job) -> None:
        if job.result is None:
            raise ValueError("Job has no result yet.")
        job.incident_error = None
        self._report(job, job.result)
        job.stage = "COMPLETED"

    # -------------------------------------------------------------- housekeeping
    def _finish(self, job: Job, status: str, stage: str) -> None:
        job.status, job.stage = status, stage
        job.finished_at, job.finished_mono = _now(), time.monotonic()

    @staticmethod
    def _remove_video(job: Job) -> None:
        try:
            os.unlink(job.video_path)
        except OSError:
            pass

    def _purge(self) -> None:
        cutoff = time.monotonic() - settings.job_ttl_seconds
        with self._lock:
            stale = [j for j in self._jobs.values() if j.finished_mono is not None and j.finished_mono < cutoff]
            for j in stale:
                self._jobs.pop(j.id, None)
        for j in stale:
            try:
                j.frame_path.unlink(missing_ok=True)
            except OSError:
                pass

    def shutdown(self) -> None:
        with self._lock:
            jobs = list(self._jobs.values())
        for j in jobs:
            j.cancel_event.set()
        self._executor.shutdown(wait=False, cancel_futures=True)


job_manager = JobManager()
