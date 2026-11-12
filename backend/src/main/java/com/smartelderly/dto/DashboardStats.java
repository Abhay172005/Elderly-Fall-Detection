package com.smartelderly.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Aggregates computed from the incidents collection for the caregiver dashboard. */
public record DashboardStats(
        long total,
        long today,
        long verified,
        long pending,
        long resolved,
        long falseAlarm,
        long awaitingAcknowledgement,
        Double averageConfidence,
        Map<String, Long> distribution,
        List<TrendPoint> trend,
        List<IncidentDto> recent,
        Instant generatedAt) {

    public record TrendPoint(LocalDate date, long count) {}
}
