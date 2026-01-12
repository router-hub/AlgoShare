package com.algoshare.auth.domain.model;

import java.io.Serializable;
import java.util.UUID;

public class SessionData implements Serializable {
    public UUID userId;
    public String email;
    public String deviceFingerprint;

    public SessionData() {}

    public SessionData(UUID userId, String email, String deviceFingerprint) {
        this.userId = userId;
        this.email = email;
        this.deviceFingerprint = deviceFingerprint;
    }

    public String getEmail() {
        return email;
    }
}
