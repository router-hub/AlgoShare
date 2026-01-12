package com.algoshare.auth.api.dto;

public class RegistrationResponseDto {

    private String userId;
    private String email;
    private String status;  // PENDING_EMAIL_VERIFICATION

    public RegistrationResponseDto(String userId, String email, String status) {
        this.userId = userId;
        this.email = email;
        this.status = status;
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
}
