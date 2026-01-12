package com.algoshare.auth.domain.exception;

/**
 * Thrown when attempting to register with an email that already exists.
 * Mapped to 409 Conflict by GlobalExceptionHandler.
 */
public class EmailAlreadyExistsException extends RuntimeException {
    
    public EmailAlreadyExistsException(String message) {
        super(message);
    }
}
