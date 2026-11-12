package com.smartelderly.dto;

import java.time.Instant;
import java.util.Map;

/** Connectivity of every component the portal depends on. */
public record HealthDto(String status, Instant timestamp, Component backend, Component database, Component mlService,
                        Component ai, Component email) {

    public record Component(String status, String detail, Long latencyMs, Map<String, Object> info) {
        public static Component of(String status, String detail) { return new Component(status, detail, null, null); }
    }
}
