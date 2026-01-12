package com.algoshare.auth.domain.service;

import com.algoshare.auth.domain.model.Role;
import com.algoshare.auth.domain.model.TokenType;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT Service - Production-ready JWT generation and validation
 * Uses Nimbus JOSE + JWT library with RS256 (RSA-SHA256) signing
 */
@Service
@Slf4j
public class JwtService {

    private final RSAKey rsaKey;
    private final JWSSigner signer;
    private final JWSVerifier verifier;
    private final String issuerUri;

    @Value("${algoshare.auth.token.access-ttl-seconds:604800}") // 7 days default
    private long accessTokenTtl;

    @Value("${algoshare.auth.token.refresh-ttl-seconds:2592000}") // 30 days default
    private long refreshTokenTtl;

    @Value("${algoshare.auth.token.pre-auth-ttl-seconds:300}") // 5 minutes default
    private long preAuthTokenTtl;

    @Value("${algoshare.auth.audience:algoshare-gateway}")
    private String audience;

    public JwtService(RSAKey rsaKey, @Value("${algoshare.auth.issuer-uri}") String issuerUri) {
        this.rsaKey = rsaKey;
        this.issuerUri = issuerUri;

        try {
            // Initialize signer and verifier
            this.signer = new RSASSASigner(rsaKey);
            this.verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
            log.info("[JWT_SERVICE_INIT] JWT Service initialized | algorithm=RS256 | keyId={}",
                    rsaKey.getKeyID());
        } catch (Exception e) {
            log.error("[JWT_SERVICE_ERROR] Failed to initialize JWT service", e);
            throw new RuntimeException("Failed to initialize JWT service", e);
        }
    }

    /**
     * Generate PRE_AUTH token (after password verification, before OTP)
     * Short-lived token (5 minutes) with minimal claims
     */
    public String generatePreAuthToken(UUID userId, String sessionId) {
        log.debug("[PRE_AUTH_TOKEN_START] Generating PRE_AUTH token | userId={} | sessionId={}",
                userId, sessionId);

        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(preAuthTokenTtl);
        String jti = UUID.randomUUID().toString();

        try {
            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .jwtID(jti)
                    .issuer(issuerUri)
                    .audience(audience)
                    .subject(userId.toString())
                    .claim("tokenType", TokenType.PRE_AUTH.toString())
                    .claim("sessionId", sessionId)
                    .claim("mfa", false)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiry))
                    .build();

            String token = signToken(claimsSet);

            log.info("[PRE_AUTH_TOKEN_SUCCESS] PRE_AUTH token generated | userId={} | jti={} | expiresIn={}s",
                    userId, jti, preAuthTokenTtl);
            return token;

        } catch (Exception e) {
            log.error("[PRE_AUTH_TOKEN_ERROR] Failed to generate PRE_AUTH token | userId={}", userId, e);
            throw new RuntimeException("Failed to generate PRE_AUTH token", e);
        }
    }

    /**
     * Generate ACCESS token (after OTP verification)
     * Long-lived token (7 days) with full claims including roles and permissions
     */
    public String generateAccessToken(UUID userId, List<Role> roles, List<String> permissions) {
        log.debug("[ACCESS_TOKEN_START] Generating ACCESS token | userId={} | roles={}", userId, roles);

        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenTtl);
        String jti = UUID.randomUUID().toString();

        try {
            JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                    .jwtID(jti)
                    .issuer(issuerUri)
                    .audience(audience)
                    .subject(userId.toString())
                    .claim("tokenType", TokenType.ACCESS.toString())
                    .claim("mfa", true)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiry));

            // Add roles
            if (roles != null && !roles.isEmpty()) {
                List<String> roleStrings = roles.stream()
                        .map(Enum::name)
                        .toList();
                claimsBuilder.claim("roles", roleStrings);
            }

            // Add permissions
            if (permissions != null && !permissions.isEmpty()) {
                claimsBuilder.claim("permissions", permissions);
            }

            JWTClaimsSet claimsSet = claimsBuilder.build();
            String token = signToken(claimsSet);

            log.info("[ACCESS_TOKEN_SUCCESS] ACCESS token generated | userId={} | jti={} | roles={} | expiresIn={}s",
                    userId, jti, roles, accessTokenTtl);
            return token;

        } catch (Exception e) {
            log.error("[ACCESS_TOKEN_ERROR] Failed to generate ACCESS token | userId={}", userId, e);
            throw new RuntimeException("Failed to generate ACCESS token", e);
        }
    }

    /**
     * Generate REFRESH token (issued with ACCESS token)
     * Very long-lived token (30 days) for token refresh
     */
    public String generateRefreshToken(UUID userId) {
        log.debug("[REFRESH_TOKEN_START] Generating REFRESH token | userId={}", userId);

        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(refreshTokenTtl);
        String jti = UUID.randomUUID().toString();

        try {
            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .jwtID(jti)
                    .issuer(issuerUri)
                    .audience(audience)
                    .subject(userId.toString())
                    .claim("tokenType", "REFRESH")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiry))
                    .build();

            String token = signToken(claimsSet);

            log.info("[REFRESH_TOKEN_SUCCESS] REFRESH token generated | userId={} | jti={} | expiresIn={}s",
                    userId, jti, refreshTokenTtl);
            return token;

        } catch (Exception e) {
            log.error("[REFRESH_TOKEN_ERROR] Failed to generate REFRESH token | userId={}", userId, e);
            throw new RuntimeException("Failed to generate REFRESH token", e);
        }
    }

    /**
     * Sign JWT claims and return serialized token string
     */
    private String signToken(JWTClaimsSet claimsSet) throws JOSEException {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaKey.getKeyID())
                .type(JOSEObjectType.JWT)
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(signer);

        return signedJWT.serialize();
    }

    /**
     * Verify and parse JWT token
     * Returns claims if valid, throws exception if invalid
     */
    public JWTClaimsSet verifyAndParse(String token) {
        log.debug("[JWT_VERIFY_START] Verifying JWT token");

        try {
            SignedJWT signedJWT = SignedJWT.parse(token);

            // Verify signature
            if (!signedJWT.verify(verifier)) {
                log.warn("[JWT_INVALID_SIGNATURE] Token signature verification failed");
                throw new RuntimeException("Invalid token signature");
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            // Verify expiration
            Date expiration = claims.getExpirationTime();
            if (expiration == null || expiration.before(new Date())) {
                log.warn("[JWT_EXPIRED] Token has expired | exp={}", expiration);
                throw new RuntimeException("Token has expired");
            }

            // Verify issuer
            if (!issuerUri.equals(claims.getIssuer())) {
                log.warn("[JWT_INVALID_ISSUER] Invalid issuer | expected={} | actual={}",
                        issuerUri, claims.getIssuer());
                throw new RuntimeException("Invalid token issuer");
            }

            // Verify audience
            if (!claims.getAudience().contains(audience)) {
                log.warn("[JWT_INVALID_AUDIENCE] Invalid audience | expected={} | actual={}",
                        audience, claims.getAudience());
                throw new RuntimeException("Invalid token audience");
            }

            log.info("[JWT_VERIFY_SUCCESS] Token verified successfully | jti={} | sub={}",
                    claims.getJWTID(), claims.getSubject());
            return claims;

        } catch (Exception e) {
            log.error("[JWT_VERIFY_ERROR] Token verification failed | error={}", e.getMessage());
            throw new RuntimeException("Token verification failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extract JWT ID (JTI) from token without full verification
     * Used for blacklist checks (faster than full verification)
     */
    public String extractJti(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            return signedJWT.getJWTClaimsSet().getJWTID();
        } catch (Exception e) {
            log.error("[JWT_PARSE_ERROR] Failed to extract JTI from token", e);
            throw new RuntimeException("Failed to parse token", e);
        }
    }

    /**
     * Extract user ID from token without full verification
     */
    public UUID extractUserId(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            String subject = signedJWT.getJWTClaimsSet().getSubject();
            return UUID.fromString(subject);
        } catch (Exception e) {
            log.error("[JWT_PARSE_ERROR] Failed to extract userId from token", e);
            throw new RuntimeException("Failed to parse token", e);
        }
    }

    /**
     * Get remaining TTL in seconds
     */
    public long getRemainingTtl(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            Date expiration = signedJWT.getJWTClaimsSet().getExpirationTime();
            if (expiration == null) {
                return 0;
            }
            long remaining = (expiration.getTime() - System.currentTimeMillis()) / 1000;
            return Math.max(remaining, 0);
        } catch (Exception e) {
            log.error("[JWT_PARSE_ERROR] Failed to get TTL from token", e);
            return 0;
        }
    }

    /**
     * Extract specific claim from token
     */
    public Object extractClaim(String token, String claimName) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            return signedJWT.getJWTClaimsSet().getClaim(claimName);
        } catch (Exception e) {
            log.error("[JWT_PARSE_ERROR] Failed to extract claim {} from token", claimName, e);
            throw new RuntimeException("Failed to parse token", e);
        }
    }
}
