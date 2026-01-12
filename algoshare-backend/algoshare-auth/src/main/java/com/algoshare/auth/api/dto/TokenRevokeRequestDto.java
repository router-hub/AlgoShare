package com.algoshare.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

public class TokenRevokeRequestDto {

    @NotBlank(message = "Token is required")
    private String token;

    // Getters and setters
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
