package com.algoshare.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

public class OtpResendRequestDto {

    @NotBlank(message = "Partial JWT is required")
    private String partialJwt;

    @NotBlank(message = "Session ID is required")
    private String sessionId;

    // Getters and setters
    public String getPartialJwt() {
        return partialJwt;
    }

    public void setPartialJwt(String partialJwt) {
        this.partialJwt = partialJwt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
