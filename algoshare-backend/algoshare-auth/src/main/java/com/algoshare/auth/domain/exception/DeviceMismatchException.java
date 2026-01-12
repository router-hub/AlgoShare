package com.algoshare.auth.domain.exception;

/**
 * Thrown when device fingerprint doesn't match session fingerprint.
 * Indicates possible token replay attack.
 * Mapped to 401 Unauthorized by GlobalExceptionHandler.
 */
public class DeviceMismatchException extends RuntimeException {
    
    public DeviceMismatchException(String message) {
        super(message);
    }
}
