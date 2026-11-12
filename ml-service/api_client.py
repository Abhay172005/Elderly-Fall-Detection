"""Reports detections to the Spring Boot backend (POST /api/detection/fall) with the shared service key."""
from __future__ import annotations

import base64
import logging
import time
from pathlib import Path
from typing import Optional

import requests

from config import settings

log = logging.getLogger("ml.api_client")


class BackendError(RuntimeError):
    pass


def build_payload(job_id: str, result: dict, params: dict, frame_path: Optional[Path]) -> dict:
    v = result.get("verification") or {}
    payload = {
        "detectionId": job_id,
        "outcome": result["outcome"],
        "confidence": float(result.get("confidence", 0.0)),
        "detectedAt": result.get("detectedAt"),
        "residentName": params.get("resident_name") or None,
        "residentAge": params.get("resident_age"),
        "cameraName": params.get("camera_name") or None,
        "detection": {
            "outcome": result["outcome"],
            "verificationStatus": result.get("verificationStatus"),
            "verificationSeconds": result.get("verificationSeconds"),
            "observedDownSeconds": result.get("observedDownSeconds"),
            "fallStartSeconds": v.get("fallStartSeconds"),
            "confirmedAtSeconds": v.get("endSeconds"),
            "fallScore": v.get("fallScore"),
            "downRatio": v.get("downRatio"),
            "poseQuality": v.get("poseQuality"),
            "postureBefore": v.get("postureBefore"),
            "postureAfter": v.get("postureAfter"),
            "framesAnalyzed": result.get("framesAnalyzed"),
            "maxPeopleDetected": result.get("maxPeopleDetected"),
            "trackId": v.get("trackId"),
            "videoName": (result.get("video") or {}).get("name"),
            "videoDurationSeconds": (result.get("video") or {}).get("durationSeconds"),
            "processingMs": result.get("processingMs"),
            "modelName": result.get("model"),
            "confidenceThreshold": result.get("confidenceThreshold"),
            "reasons": v.get("reasons", []),
        },
    }
    if frame_path is not None and frame_path.is_file():
        payload["frameImageBase64"] = base64.b64encode(frame_path.read_bytes()).decode("ascii")
    return payload


def send_detection(payload: dict, attempts: int = 3) -> dict:
    """POSTs the detection; retries on connection errors and 5xx. Returns the created incident JSON."""
    url = f"{settings.backend_url}/detection/fall"
    headers = {"X-Service-Key": settings.service_key} if settings.service_key else {}
    last_error = "unknown error"
    for attempt in range(1, attempts + 1):
        try:
            response = requests.post(url, json=payload, headers=headers, timeout=90)
            if response.status_code in (200, 201):
                return response.json()
            if response.status_code in (401, 403):
                raise BackendError("Backend rejected the ML service key (check ML_SERVICE_API_KEY in both services).")
            if 400 <= response.status_code < 500:
                try:
                    detail = response.json().get("message")
                except ValueError:
                    detail = None
                raise BackendError(f"Backend rejected the incident ({response.status_code}): {detail or 'invalid request'}")
            last_error = f"backend returned {response.status_code}"
        except requests.RequestException as exc:
            last_error = f"backend unreachable ({type(exc).__name__})"
        log.warning("Sending detection %s failed (attempt %d/%d): %s", payload.get("detectionId"), attempt, attempts, last_error)
        if attempt < attempts:
            time.sleep(2 * attempt)
    raise BackendError(f"Could not deliver the incident to the backend: {last_error}")
