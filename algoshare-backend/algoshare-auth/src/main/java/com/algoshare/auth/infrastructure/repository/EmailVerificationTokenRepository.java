package com.algoshare.auth.infrastructure.repository;

import com.algoshare.auth.infrastructure.entity.EmailVerificationTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for email verification tokens
 */
@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationTokenEntity, UUID> {

    /**
     * Find token by its SHA-256 hash
     */
    Optional<EmailVerificationTokenEntity> findByTokenHash(String tokenHash);

    /**
     * Delete all tokens for a specific user
     * Used when user verifies email or during cleanup
     */
    @Modifying
    @Query("DELETE FROM EmailVerificationTokenEntity e WHERE e.userId = :userId")
    void deleteByUserId(UUID userId);

    /**
     * Delete all expired tokens
     * Should be called periodically for cleanup
     */
    @Modifying
    @Query("DELETE FROM EmailVerificationTokenEntity e WHERE e.expiresAt < :now")
    void deleteExpiredTokens(Instant now);

    /**
     * Check if user has pending verification token
     */
    boolean existsByUserId(UUID userId);
}
