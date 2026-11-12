package com.smartelderly.dto;

import com.smartelderly.model.DetectionDetails;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Validated request bodies for incident endpoints. */
public final class IncidentRequests {
    private IncidentRequests() {}

    /** Sent by the ML service after temporal verification (POST /api/detection/fall). */
    public record DetectionReport(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Invalid detection id") String detectionId,
            @NotBlank @Pattern(regexp = "FALL_CONFIRMED|INCONCLUSIVE", message = "outcome must be FALL_CONFIRMED or INCONCLUSIVE") String outcome,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
            Instant detectedAt,
            @Size(max = 80) String residentName,
            @Min(0) @Max(130) Integer residentAge,
            @Size(max = 80) String cameraName,
            // JPEG/PNG of the fall frame, base64 encoded (about 7 MB of base64 is 5 MB of image).
            @Size(max = 7_000_000, message = "Frame image is too large") String frameImageBase64,
            DetectionDetails detection) {}

    /** Manually reported incident (POST /api/incidents), e.g. a fall witnessed by staff. */
    public record ManualIncident(
            @NotBlank(message = "Resident name is required") @Size(max = 80) String elderlyName,
            @Min(0) @Max(130) Integer elderlyAge,
            @NotBlank(message = "Camera / location is required") @Size(max = 80) String cameraName,
            @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
            @Pattern(regexp = "VERIFIED|PENDING", message = "status must be VERIFIED or PENDING") String status,
            Instant timestamp,
            @Size(max = 1000) String note) {}

    /** Editable incident fields (PUT /api/incidents/{id}). Null fields are left unchanged. */
    public record IncidentUpdate(
            @Size(min = 1, max = 80) String elderlyName,
            @Min(0) @Max(130) Integer elderlyAge,
            @Size(min = 1, max = 80) String cameraName,
            @Pattern(regexp = "VERIFIED|PENDING|RESOLVED|FALSE_ALARM", message = "Unknown status") String status,
            @Size(max = 1000) String note) {}

    public record AcknowledgeRequest(@Size(max = 1000) String note) {}

    public record ResolveRequest(
            @Size(max = 1000) String note,
            @Pattern(regexp = "RESOLVED|FALSE_ALARM", message = "outcome must be RESOLVED or FALSE_ALARM") String outcome) {}
}
