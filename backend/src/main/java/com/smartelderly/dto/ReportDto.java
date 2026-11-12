package com.smartelderly.dto;

import java.time.Instant;

public record ReportDto(String id, String incidentId, String elderlyName, String status, Instant timestamp,
                        boolean pdfAvailable, Instant pdfGeneratedAt, Long sizeBytes) {
}
