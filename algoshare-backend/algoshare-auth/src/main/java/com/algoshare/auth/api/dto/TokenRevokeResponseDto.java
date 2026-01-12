package com.algoshare.auth.api.dto;

import java.time.Instant;

public class TokenRevokeResponseDto {

    private String message;
    private Instant revokedAt;

    public TokenRevokeResponseDto(String message, Instant revokedAt) {
        this.message = message;
        this.revokedAt = revokedAt;
    }

    // Getters
    public String getMessage() {
        return message;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
