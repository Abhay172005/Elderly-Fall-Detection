"""FastAPI entry point of the Smart Elderly Fall Detection ML service.

Endpoints
  GET    /health                          service + model readiness
  GET    /model/status                    YOLO Pose model details and inference statistics
  POST   /detect/video                    synchronous analysis (waits for the result)
  POST   /detect/video/jobs               asynchronous analysis job (used by the Spring Boot backend)
  GET    /detect/video/jobs/{id}          job progress / result
  DELETE /detect/video/jobs/{id}          cancel a job
  GET    /detect/video/jobs/{id}/frame    annotated evidence frame (JPEG)
  POST   /detect/video/jobs/{id}/report   retry delivering the incident to the backend
"""
from __future__ import annotations

import hmac
import logging
import shutil
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

from config import settings
from detector import VideoValidationError, model_manager
from jobs import job_manager

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("ml.main")

VERSION = "2.0.0"
ALLOWED_EXTENSIONS = {".mp4", ".webm", ".mov", ".avi", ".mkv"}
CHUNK = 1024 * 1024


@asynccontextmanager
async def lifespan(_: FastAPI):
    if not settings.service_key:
        log.warning("ML_SERVICE_API_KEY is not set: detection endpoints are unauthenticated (development only).")
    await run_in_threadpool(model_manager.load)
    yield
    job_manager.shutdown()


app = FastAPI(title="Smart Elderly Fall Detection ML Service", version=VERSION, lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=list(settings.cors_origins), allow_methods=["GET", "POST", "DELETE"],
                   allow_headers=["Content-Type", "X-Service-Key"])


def require_service_key(x_service_key: Optional[str] = Header(default=None)) -> None:
    if settings.service_key and not hmac.compare_digest((x_service_key or "").encode(), settings.service_key.encode()):
        raise HTTPException(status_code=401, detail="Missing or invalid service key.")


@app.get("/health")
def health():
    counts = job_manager.counts()
    return {
        "status": "ready" if model_manager.loaded else "degraded",
        "modelLoaded": model_manager.loaded,
        "model": model_manager.name,
        "device": model_manager.device,
        "activeJobs": counts["active"],
        "queuedJobs": counts["queued"],
        "version": VERSION,
    }


@app.get("/model/status")
def model_status():
    return model_manager.status()


async def _save_upload(video: UploadFile) -> tuple[Path, str]:
    name = Path(video.filename or "video.mp4").name
    suffix = Path(name).suffix.lower()
    if suffix not in ALLOWED_EXTENSIONS:
        raise HTTPException(status_code=400, detail="Unsupported video format. Use MP4, WEBM, MOV, AVI or MKV.")
    target = settings.storage_dir / "uploads" / f"{uuid.uuid4().hex}{suffix}"
    limit = settings.max_upload_mb * 1024 * 1024
    size = 0
    try:
        with target.open("wb") as out:
            while chunk := await video.read(CHUNK):
                size += len(chunk)
                if size > limit:
                    raise HTTPException(status_code=413, detail=f"Video exceeds the {settings.max_upload_mb} MB limit.")
                out.write(chunk)
    except BaseException:
        target.unlink(missing_ok=True)
        raise
    if size == 0:
        target.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail="The uploaded file is empty.")
    return target, name


def _params(confidence_threshold, verification_seconds, record_inconclusive, report_incident,
            resident_name, resident_age, camera_name) -> dict:
    if not (0.3 <= confidence_threshold <= 0.99):
        raise HTTPException(status_code=422, detail="confidence_threshold must be between 0.30 and 0.99.")
    if not (2 <= verification_seconds <= 30):
        raise HTTPException(status_code=422, detail="verification_seconds must be between 2 and 30.")
    if resident_age is not None and not (0 <= resident_age <= 130):
        raise HTTPException(status_code=422, detail="resident_age must be between 0 and 130.")
    return {
        "confidence_threshold": confidence_threshold,
        "verification_seconds": verification_seconds,
        "record_inconclusive": record_inconclusive,
        "report_incident": report_incident,
        "resident_name": (resident_name or "").strip()[:80],
        "resident_age": resident_age,
        "camera_name": (camera_name or "").strip()[:80],
    }


def _ensure_model():
    if not model_manager.loaded:
        raise HTTPException(status_code=503, detail="The YOLO Pose model is not loaded" +
                            (f": {model_manager.error}" if model_manager.error else "."))


@app.post("/detect/video/jobs", status_code=202, dependencies=[Depends(require_service_key)])
async def create_job(video: UploadFile = File(...),
                     confidence_threshold: float = Form(settings.default_confidence_threshold),
                     verification_seconds: float = Form(settings.default_verification_seconds),
                     record_inconclusive: bool = Form(True),
                     report_incident: bool = Form(True),
                     resident_name: str = Form(""),
                     resident_age: Optional[int] = Form(None),
                     camera_name: str = Form("")):
    _ensure_model()
    params = _params(confidence_threshold, verification_seconds, record_inconclusive, report_incident,
                     resident_name, resident_age, camera_name)
    path, name = await _save_upload(video)
    job = job_manager.submit(path, name, params)
    return job.snapshot()


@app.post("/detect/video", dependencies=[Depends(require_service_key)])
async def detect_video(video: UploadFile = File(...),
                       confidence_threshold: float = Form(settings.default_confidence_threshold),
                       verification_seconds: float = Form(settings.default_verification_seconds),
                       record_inconclusive: bool = Form(True),
                       report_incident: bool = Form(True),
                       resident_name: str = Form(""),
                       resident_age: Optional[int] = Form(None),
                       camera_name: str = Form("")):
    """Synchronous variant: same pipeline, waits until the analysis is finished."""
    _ensure_model()
    params = _params(confidence_threshold, verification_seconds, record_inconclusive, report_incident,
                     resident_name, resident_age, camera_name)
    path, name = await _save_upload(video)
    job = job_manager.submit(path, name, params)
    await run_in_threadpool(job.future.result)
    snap = job.snapshot()
    if job.status == "FAILED":
        return JSONResponse(status_code=422, content=snap)
    return snap


def _job_or_404(job_id: str):
    job = job_manager.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Detection job not found or expired.")
    return job


@app.get("/detect/video/jobs/{job_id}", dependencies=[Depends(require_service_key)])
def get_job(job_id: str):
    return _job_or_404(job_id).snapshot()


@app.delete("/detect/video/jobs/{job_id}", dependencies=[Depends(require_service_key)])
def cancel_job(job_id: str):
    _job_or_404(job_id)
    return job_manager.cancel(job_id).snapshot()


@app.get("/detect/video/jobs/{job_id}/frame", dependencies=[Depends(require_service_key)])
def job_frame(job_id: str):
    job = _job_or_404(job_id)
    if not job.frame_path.is_file():
        raise HTTPException(status_code=404, detail="No evidence frame was captured for this job.")
    return FileResponse(job.frame_path, media_type="image/jpeg")


@app.post("/detect/video/jobs/{job_id}/report", dependencies=[Depends(require_service_key)])
async def retry_report(job_id: str):
    job = _job_or_404(job_id)
    if job.status != "COMPLETED":
        raise HTTPException(status_code=409, detail="Only completed jobs can be reported.")
    await run_in_threadpool(job_manager.retry_report, job)
    return job.snapshot()


@app.exception_handler(VideoValidationError)
async def video_error(_, exc: VideoValidationError):
    return JSONResponse(status_code=422, content={"detail": str(exc)})


def cleanup_uploads() -> None:
    """Removes orphaned uploads left by a previous crash."""
    shutil.rmtree(settings.storage_dir / "uploads", ignore_errors=True)
    (settings.storage_dir / "uploads").mkdir(parents=True, exist_ok=True)


if __name__ == "__main__":
    import uvicorn

    cleanup_uploads()
    uvicorn.run(app, host=settings.host, port=settings.port)
