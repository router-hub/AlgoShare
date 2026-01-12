package com.algoshare.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

public class VerifyEmailRequestDto {

    @NotBlank(message = "Verification token is required")
    private String token;

    // Getters and setters
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
