package com.algoshare.auth.api.dto;

public class LoginResponseDto {

    private String status;        // OTP_REQUIRED
    private String partialJwt;    // PRE_AUTH token
    private String sessionId;
    private int expiresIn;        // 300 seconds (5 minutes)

    public LoginResponseDto(String status, String partialJwt, String sessionId, int expiresIn) {
        this.status = status;
        this.partialJwt = partialJwt;
        this.sessionId = sessionId;
        this.expiresIn = expiresIn;
    }

    // Getters
    public String getStatus() {
        return status;
    }

    public String getPartialJwt() {
        return partialJwt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getExpiresIn() {
        return expiresIn;
    }
}
