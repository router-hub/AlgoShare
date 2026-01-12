package com.algoshare.auth.domain.exception;

/**
 * Thrown when a session has expired or is not found.
 * Mapped to 401 Unauthorized by GlobalExceptionHandler.
 */
public class SessionExpiredException extends RuntimeException {
    
    public SessionExpiredException(String message) {
        super(message);
    }
}
