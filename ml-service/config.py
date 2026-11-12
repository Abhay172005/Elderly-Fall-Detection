"""Runtime configuration for the ML service, read from environment variables / the project .env file."""
import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent
# A local ml-service/.env wins over the shared project-root .env.
load_dotenv(BASE_DIR / ".env")
load_dotenv(BASE_DIR.parent / ".env")


def _float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, default))
    except (TypeError, ValueError):
        return default


def _int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, default))
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class Settings:
    host: str = os.getenv("ML_HOST", "127.0.0.1")
    port: int = _int("ML_PORT", 8000)
    model_path: str = os.getenv("YOLO_MODEL", str(BASE_DIR / "yolov8n-pose.pt"))
    # 0 = adaptive: the video's longest side, clamped to 640..1280 (small/distant people need more pixels)
    inference_size: int = _int("YOLO_IMGSZ", 0)
    person_confidence: float = _float("YOLO_PERSON_CONFIDENCE", 0.25)
    analysis_fps: float = _float("ANALYSIS_FPS", 6.0)
    max_upload_mb: int = _int("MAX_UPLOAD_MB", 200)
    max_video_seconds: int = _int("MAX_VIDEO_SECONDS", 900)
    max_concurrent_jobs: int = _int("MAX_CONCURRENT_JOBS", 2)
    job_ttl_seconds: int = _int("JOB_TTL_SECONDS", 3600)
    default_confidence_threshold: float = _float("FALL_CONFIDENCE_THRESHOLD", 0.70)
    default_verification_seconds: float = _float("FALL_VERIFICATION_SECONDS", 5.0)
    backend_url: str = os.getenv("BACKEND_URL", "http://localhost:8080/api").rstrip("/")
    service_key: str = os.getenv("ML_SERVICE_API_KEY", "")
    cors_origins: tuple = tuple(o.strip() for o in os.getenv("CORS_ALLOWED_ORIGINS", "http://localhost:5173").split(",") if o.strip())
    storage_dir: Path = BASE_DIR / "storage"


settings = Settings()
