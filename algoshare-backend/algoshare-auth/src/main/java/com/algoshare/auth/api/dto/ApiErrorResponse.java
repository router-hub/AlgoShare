package com.algoshare.auth.api.dto;

import java.time.Instant;

/**
 * Unified error response for all API errors.
 * Used by GlobalExceptionHandler for consistent error structure.
 * 
 * Example:
 * {
 *   "error": "EMAIL_ALREADY_EXISTS",
 *   "message": "User already registered with this email",
 *   "traceId": "abc-123",
 *   "timestamp": "2026-01-11T18:30:00Z"
 * }
 */
public class ApiErrorResponse {

    private String error;       // Machine-readable error code
    private String message;     // Human-readable error message
    private String traceId;     // Trace ID for distributed tracing
    private Instant timestamp;  // When the error occurred

    public ApiErrorResponse(String error, String message, String traceId) {
        this.error = error;
        this.message = message;
        this.traceId = traceId;
        this.timestamp = Instant.now();
    }

    // Getters
    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
