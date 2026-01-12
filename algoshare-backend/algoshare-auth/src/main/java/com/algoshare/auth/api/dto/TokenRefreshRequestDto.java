package com.algoshare.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

public class TokenRefreshRequestDto {

    @NotBlank(message = "Refresh token is required")
    private String refreshToken;

    // Getters and setters
    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
