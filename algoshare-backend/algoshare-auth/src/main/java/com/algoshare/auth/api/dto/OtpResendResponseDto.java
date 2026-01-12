package com.algoshare.auth.api.dto;

public class OtpResendResponseDto {

    private String message;
    private int remainingResends;
    private int retryAfter;       // Cooldown in seconds

    public OtpResendResponseDto(String message, int remainingResends, int retryAfter) {
        this.message = message;
        this.remainingResends = remainingResends;
        this.retryAfter = retryAfter;
    }

    // Getters
    public String getMessage() {
        return message;
    }

    public int getRemainingResends() {
        return remainingResends;
    }

    public int getRetryAfter() {
        return retryAfter;
    }
}
