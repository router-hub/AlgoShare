package com.algoshare.auth.domain.exception;

/**
 * Thrown when login credentials are invalid.
 * Mapped to 401 Unauthorized by GlobalExceptionHandler.
 */
public class InvalidCredentialsException extends RuntimeException {
    
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
