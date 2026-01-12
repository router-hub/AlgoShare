package com.algoshare.auth.domain.service;

import com.algoshare.auth.domain.constants.AuthConstants;
import com.algoshare.auth.domain.utils.CryptoUtils;
import com.algoshare.auth.infrastructure.entity.EmailVerificationTokenEntity;
import com.algoshare.auth.infrastructure.repository.EmailVerificationTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Token Service - Token generation, hashing, and validation
 * Uses 256-bit random tokens with SHA-256 hashing for storage
 */
@Service
@Slf4j
public class TokenService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final SecureRandom secureRandom;
    private final CryptoUtils cryptoUtils;

    public TokenService(EmailVerificationTokenRepository tokenRepository,
                        SecureRandom secureRandom,
                        CryptoUtils cryptoUtils) {
        this.tokenRepository = tokenRepository;
        this.secureRandom = secureRandom;
        this.cryptoUtils = cryptoUtils;
    }

    /**
     * Generate cryptographically secure 256-bit random token
     * Returns Base64-encoded string for URL safety
     */
    public String generateRawToken() {
        byte[] randomBytes = new byte[AuthConstants.TOKEN_BYTE_LENGTH]; // 256 bits
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        
        log.debug("[TOKEN_GENERATED] Raw token generated | length={} bits", randomBytes.length * 8);
        return rawToken;
    }

    /**
     * Hashes a raw token for secure database storage.
     * Use SHA-256 because high entropy tokens don't require salting/work factors like passwords.
     */
    public String hashToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Raw token cannot be null or empty");
        }
        return cryptoUtils.hashToHex(rawToken);
    }

    /**
     * Create verification token entity and store in DB
     * Only hash is stored, not raw token
     */
    public void createVerificationToken(UUID userId, String rawToken) {
        log.info("[TOKEN_CREATE_START] Creating verification token | userId={}", userId);

        String tokenHash = hashToken(rawToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(AuthConstants.EXPIRATION_HOURS * 60 * 60);

        EmailVerificationTokenEntity token = new EmailVerificationTokenEntity();
        token.setTokenHash(tokenHash);
        token.setUserId(userId);
        token.setCreatedAt(now);
        token.setExpiresAt(expiresAt);

        EmailVerificationTokenEntity savedToken = tokenRepository.save(token);
        
        log.info("[TOKEN_CREATED] Verification token stored | userId={} | tokenId={} | expiresAt={}", 
            userId, savedToken.getId(), expiresAt);

    }

    /**
     * Validate raw token by hashing and checking DB
     * Returns token entity if valid and not expired
     */
    public EmailVerificationTokenEntity validateToken(String rawToken) {
        log.info("[TOKEN_VALIDATE_START] Validating verification token");

        String tokenHash = hashToken(rawToken);
        EmailVerificationTokenEntity token = tokenRepository.findByTokenHash(tokenHash)
                .orElse(null);

        if (token == null) {
            log.warn("[TOKEN_INVALID] Token not found in database | tokenHash={}", 
                tokenHash.substring(0, 8) + "...");
            return null;
        }

        if (token.getExpiresAt().isBefore(Instant.now())) {
            log.warn("[TOKEN_EXPIRED] Token has expired | userId={} | expiredAt={}", 
                token.getUserId(), token.getExpiresAt());
            return null;
        }

        log.info("[TOKEN_VALID] Token validated successfully | userId={} | tokenId={}", 
            token.getUserId(), token.getId());
        return token;
    }

    /**
     * Delete all tokens for a user (after verification or cleanup)
     */
    public void deleteTokensByUserId(UUID userId) {
        log.info("[TOKEN_DELETE] Deleting verification tokens | userId={}", userId);
        tokenRepository.deleteByUserId(userId);
        log.debug("[TOKEN_DELETED] Tokens deleted for user | userId={}", userId);
    }

    /**
     * Cleanup expired tokens (scheduled task)
     */
    public void deleteExpiredTokens() {
        log.info("[TOKEN_CLEANUP_START] Cleaning up expired tokens");
        Instant now = Instant.now();
        tokenRepository.deleteExpiredTokens(now);
        log.info("[TOKEN_CLEANUP_DONE] Expired tokens cleaned up | cutoffTime={}", now);
    }
}
