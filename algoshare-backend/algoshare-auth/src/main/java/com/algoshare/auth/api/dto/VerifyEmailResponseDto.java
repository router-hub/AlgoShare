package com.algoshare.auth.api.dto;

import java.time.Instant;

public class VerifyEmailResponseDto {

    private String userId;
    private String email;
    private String status;      // EMAIL_VERIFIED
    private Instant verifiedAt;

    public VerifyEmailResponseDto(String userId, String email, String status, Instant verifiedAt) {
        this.userId = userId;
        this.email = email;
        this.status = status;
        this.verifiedAt = verifiedAt;
    }

    // Getters
    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getStatus() {
        return status;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }
}
