package com.algoshare.auth.infrastructure.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Email Verification Token Entity
 * Stores SHA-256 hash of verification token (not raw token)
 * Raw token sent via email, hash stored for security
 */
@Entity
@Table(name = "email_verification_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerificationTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /**
     * SHA-256 hash of the raw token
     * Raw token is 256-bit random string sent in email
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /**
     * User this token belongs to
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Token expiry time (24 hours from creation)
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * When token was created
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (expiresAt == null) {
            // Default 24 hours expiry
            expiresAt = createdAt.plusSeconds(24 * 60 * 60);
        }
    }
}
