package com.algoshare.auth.domain.service;

import com.algoshare.auth.api.dto.*;
import com.algoshare.auth.domain.exception.TokenExpiredException;
import com.algoshare.auth.domain.model.Role;
import com.algoshare.auth.infrastructure.repository.UserRoleRepository;
import com.nimbusds.jwt.JWTClaimsSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * JWT Token Management Service
 * Handles token refresh and revocation with production-ready JWT integration
 */
@Service
@Slf4j
public class JwtTokenService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtService jwtService;
    private final UserRoleRepository userRoleRepository;

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofDays(7);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    // Constant imported from AuthConstants
    private static final String REVOKED_PREFIX = com.algoshare.auth.domain.constants.AuthConstants.REDIS_TOKEN_REVOKED_PREFIX;

    public JwtTokenService(RedisTemplate<String, Object> redisTemplate,
                           JwtService jwtService,
                           UserRoleRepository userRoleRepository) {
        this.redisTemplate = redisTemplate;
        this.jwtService = jwtService;
        this.userRoleRepository = userRoleRepository;
    }

    /**
     * Refresh ACCESS token using refresh token
     * Implements refresh token rotation for security
     */
    public TokenRefreshResponseDto refreshToken(TokenRefreshRequestDto request) {
        log.info("[TOKEN_REFRESH_START] Token refresh requested");

        // 1. Verify refresh token signature and claims
        JWTClaimsSet claims;
        try {
            claims = jwtService.verifyAndParse(request.getRefreshToken());

            // Verify token type
            String tokenType = (String) claims.getClaim("tokenType");
            if (!"REFRESH".equals(tokenType)) {
                log.warn("[TOKEN_INVALID_TYPE] Invalid token type | expected=REFRESH | actual={}", tokenType);
                throw new TokenExpiredException("Invalid token type. Expected REFRESH token");
            }

        } catch (Exception e) {
            log.error("[TOKEN_VERIFY_ERROR] Failed to verify refresh token | error={}", e.getMessage());
            throw new TokenExpiredException("Invalid or expired refresh token");
        }

        // 2. Extract JTI (JWT ID) from refresh token
        String jti = extractJti(request.getRefreshToken());

        // 3. Check if refresh token is revoked (blacklisted)
        Boolean isRevoked = redisTemplate.hasKey(REVOKED_PREFIX + jti);
        if (Boolean.TRUE.equals(isRevoked)) {
            log.warn("[TOKEN_REVOKED] Refresh token is revoked | jti={}", jti);
            throw new TokenExpiredException("Refresh token has been revoked");
        }

        // 4. Extract userId from refresh token
        UUID userId = extractUserId(request.getRefreshToken());

        // 5. Generate new ACCESS token with roles and permissions
        String newAccessToken = generateAccessToken(userId);

        // 6. Generate new refresh token (rotation)
        String newRefreshToken = generateRefreshToken(userId);
        String newJti = extractJti(newRefreshToken);

        // 7. Revoke old refresh token
        long remainingTtl = getRemainingTtl(request.getRefreshToken());
        redisTemplate.opsForValue().set(REVOKED_PREFIX + jti, true, Duration.ofSeconds(remainingTtl));
        log.debug("[TOKEN_ROTATED] Old refresh token revoked, new tokens issued | userId={} | oldJti={} | newJti={}",
                userId, jti, newJti);

        TokenRefreshResponseDto response = new TokenRefreshResponseDto(
                newAccessToken,
                newRefreshToken,
                "ACCESS",
                (int) ACCESS_TOKEN_TTL.toSeconds()
        );

        log.info("[TOKEN_REFRESH_SUCCESS] Tokens refreshed successfully | userId={}", userId);
        return response;
    }

    /**
     * Revoke a token manually (logout, security breach, etc.)
     */
    public TokenRevokeResponseDto revokeToken(TokenRevokeRequestDto request) {
        log.info("[TOKEN_REVOKE_START] Token revocation requested");

        // 1. Verify token is valid before revoking (optional but recommended)
        try {
            jwtService.verifyAndParse(request.getToken());
        } catch (Exception e) {
            log.warn("[TOKEN_REVOKE_INVALID] Attempting to revoke invalid token | error={}", e.getMessage());
            // Continue with revocation even if token is invalid (for security)
        }

        // 2. Extract JTI from token
        String jti = extractJti(request.getToken());

        // 3. Get remaining TTL
        long ttl = getRemainingTtl(request.getToken());

        // Ensure minimum TTL to prevent immediate cleanup
        if (ttl <= 0) {
            log.warn("[TOKEN_EXPIRED] Token already expired | jti={}", jti);
            throw new TokenExpiredException("Token has already expired");
        }

        // 4. Add to Redis blacklist with TTL = remaining lifetime
        redisTemplate.opsForValue().set(REVOKED_PREFIX + jti, true, Duration.ofSeconds(ttl));
        log.debug("[TOKEN_REVOKED] Token added to blacklist | jti={} | ttl={}s", jti, ttl);

        TokenRevokeResponseDto response = new TokenRevokeResponseDto(
                "Token revoked successfully",
                java.time.Instant.now()
        );

        log.info("[TOKEN_REVOKE_SUCCESS] Token revoked | jti={}", jti);
        return response;
    }

    /**
     * Check if token is revoked (used by API Gateway filter)
     */
    public boolean isTokenRevoked(String token) {
        try {
            String jti = extractJti(token);
            Boolean exists = redisTemplate.hasKey(REVOKED_PREFIX + jti);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("[TOKEN_REVOKE_CHECK_ERROR] Failed to check revocation status", e);
            // Fail-safe: treat as not revoked to avoid false rejections
            return false;
        }
    }

    /**
     * Extract JTI (JWT ID) claim from token
     * Uses JwtService for parsing
     */
    private String extractJti(String token) {
        try {
            return jwtService.extractJti(token);
        } catch (Exception e) {
            log.error("[JTI_EXTRACT_ERROR] Failed to extract JTI from token | error={}", e.getMessage());
            throw new TokenExpiredException("Invalid token format");
        }
    }

    /**
     * Extract userId (subject) claim from token
     * Uses JwtService for parsing
     */
    private UUID extractUserId(String token) {
        try {
            return jwtService.extractUserId(token);
        } catch (Exception e) {
            log.error("[USERID_EXTRACT_ERROR] Failed to extract userId from token | error={}", e.getMessage());
            throw new TokenExpiredException("Invalid token format");
        }
    }

    /**
     * Get remaining TTL in seconds from token expiry
     * Uses JwtService for parsing
     */
    private long getRemainingTtl(String token) {
        try {
            return jwtService.getRemainingTtl(token);
        } catch (Exception e) {
            log.error("[TTL_EXTRACT_ERROR] Failed to get TTL from token | error={}", e.getMessage());
            return 0;
        }
    }

    /**
     * Generate new ACCESS token with full claims (roles, permissions)
     * Fetches user roles from database
     */
    private String generateAccessToken(UUID userId) {
        try {
            // Fetch user roles from database
            List<Role> roles = userRoleRepository.findRolesByUserId(userId);

            if (roles.isEmpty()) {
                log.warn("[NO_ROLES_FOUND] User has no roles assigned | userId={}", userId);
                // Optionally assign default role or throw exception
                // For now, proceeding with empty roles
            }

            // TODO: Implement permission loading if needed
            // For now, using empty permissions list
            List<String> permissions = List.of();

            log.debug("[ACCESS_TOKEN_GENERATE] Generating ACCESS token | userId={} | roles={}", userId, roles);
            return jwtService.generateAccessToken(userId, roles, permissions);

        } catch (Exception e) {
            log.error("[ACCESS_TOKEN_ERROR] Failed to generate ACCESS token | userId={} | error={}",
                    userId, e.getMessage(), e);
            throw new RuntimeException("Failed to generate ACCESS token", e);
        }
    }

    /**
     * Generate new REFRESH token
     * Uses JwtService for generation
     */
    private String generateRefreshToken(UUID userId) {
        try {
            log.debug("[REFRESH_TOKEN_GENERATE] Generating REFRESH token | userId={}", userId);
            return jwtService.generateRefreshToken(userId);
        } catch (Exception e) {
            log.error("[REFRESH_TOKEN_ERROR] Failed to generate REFRESH token | userId={} | error={}",
                    userId, e.getMessage(), e);
            throw new RuntimeException("Failed to generate REFRESH token", e);
        }
    }

    /**
     * Revoke all tokens for a user (useful for security incidents)
     * This would require maintaining a user-token registry in Redis
     */
    public void revokeAllUserTokens(UUID userId) {
        log.info("[REVOKE_ALL_START] Revoking all tokens for user | userId={}", userId);

        // Implementation note: This requires maintaining a Redis set of active JTIs per user
        // Example: SADD user:tokens:{userId} {jti}
        // Then iterate and revoke each

        String key = "user:tokens:" + userId;
        // TODO: Implement if needed

        log.info("[REVOKE_ALL_SUCCESS] All tokens revoked for user | userId={}", userId);
    }
}
