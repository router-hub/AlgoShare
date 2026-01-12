package com.algoshare.auth.domain.exception;

/**
 * Thrown when an account is temporarily locked due to too many failed attempts.
 * Mapped to 429 Too Many Requests by GlobalExceptionHandler.
 */
public class AccountLockedException extends RuntimeException {
    
    private final int retryAfterSeconds;
    
    public AccountLockedException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
    
    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
