package com.algoshare.auth.api.dto;

public class TokenRefreshResponseDto {

    private String token;            // New ACCESS token
    private String refreshToken;     // Rotated refresh token
    private String tokenType;        // ACCESS
    private int expiresIn;

    public TokenRefreshResponseDto(String token, String refreshToken, String tokenType, int expiresIn) {
        this.token = token;
        this.refreshToken = refreshToken;
        this.tokenType = tokenType;
        this.expiresIn = expiresIn;
    }

    // Getters
    public String getToken() {
        return token;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public int getExpiresIn() {
        return expiresIn;
    }
}
