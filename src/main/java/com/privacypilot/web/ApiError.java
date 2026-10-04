package com.privacypilot.web;

import java.time.Instant;
import java.util.List;

/**
 * Clean error payload returned to clients (no stack traces).
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        List<String> details) {

    public static ApiError of(int status, String error, String message, List<String> details) {
        return new ApiError(Instant.now(), status, error, message, details);
    }
}
