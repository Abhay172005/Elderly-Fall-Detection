package com.smartelderly.dto;

import com.smartelderly.model.DetectionDetails;

import java.time.Instant;

/** Incident as exposed to the frontend. Internal file paths are replaced by availability flags. */
public record IncidentDto(
        String id,
        String incidentId,
        String detectionId,
        String source,
        String elderlyName,
        Integer elderlyAge,
        String cameraName,
        Instant timestamp,
        double confidence,
        String status,
        boolean imageAvailable,
        DetectionDetails detection,
        String aiSummary,
        String caregiverRecommendation,
        String aiSource,
        String aiModel,
        boolean pdfAvailable,
        Instant pdfGeneratedAt,
        String emailStatus,
        String emailDetail,
        Instant emailAttemptedAt,
        Instant acknowledgedAt,
        String acknowledgedBy,
        String acknowledgementNote,
        Instant resolvedAt,
        String resolvedBy,
        String resolutionNote,
        Instant createdAt,
        Instant updatedAt) {
}
