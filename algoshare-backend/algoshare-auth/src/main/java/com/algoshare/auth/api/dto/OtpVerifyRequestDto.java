package com.algoshare.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class OtpVerifyRequestDto {

    @NotBlank(message = "Partial JWT is required")
    private String partialJwt;

    @NotBlank(message = "OTP is required")
    @Pattern(regexp = "^\\d{6}$", message = "OTP must be 6 digits")
    private String otp;

    @NotBlank(message = "Session ID is required")
    private String sessionId;

    // Getters and setters
    public String getPartialJwt() {
        return partialJwt;
    }

    public void setPartialJwt(String partialJwt) {
        this.partialJwt = partialJwt;
    }

    public String getOtp() {
        return otp;
    }

    public void setOtp(String otp) {
        this.otp = otp;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
