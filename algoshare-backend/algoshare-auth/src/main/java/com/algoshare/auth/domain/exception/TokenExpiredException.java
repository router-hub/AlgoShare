package com.algoshare.auth.domain.exception;

/**
 * Thrown when a token has expired.
 * Mapped to 401 Unauthorized by GlobalExceptionHandler.
 */
public class TokenExpiredException extends RuntimeException {
    
    public TokenExpiredException(String message) {
        super(message);
    }
}
