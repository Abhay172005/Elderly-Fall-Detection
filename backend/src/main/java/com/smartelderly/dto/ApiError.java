package com.smartelderly.dto;

import java.time.Instant;
import java.util.Map;

/** Uniform error body returned by every endpoint. */
public record ApiError(Instant timestamp, int status, String error, String message, String path,
                       Map<String, String> fieldErrors) {
}
