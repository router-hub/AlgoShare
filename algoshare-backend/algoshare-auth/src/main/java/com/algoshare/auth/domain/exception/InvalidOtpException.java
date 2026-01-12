package com.algoshare.auth.domain.exception;

/**
 * Thrown when OTP validation fails.
 * Mapped to 401 Unauthorized by GlobalExceptionHandler.
 */
public class InvalidOtpException extends RuntimeException {
    
    public InvalidOtpException(String message) {
        super(message);
    }
}
