package com.algoshare.auth.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@Slf4j
public class LogoutService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtService jwtService; // ADD THIS

    public LogoutService(RedisTemplate<String, Object> redisTemplate,
                         JwtService jwtService) { // UPDATE CONSTRUCTOR
        this.redisTemplate = redisTemplate;
        this.jwtService = jwtService;
    }

    public void logout(String authHeader) {
        log.info("[LOGOUT_START] Logout initiated");

        String token = extractToken(authHeader);
        if (token == null) {
            log.warn("[LOGOUT_FAILED] Invalid Authorization header");
            throw new IllegalArgumentException("Invalid Authorization header");
        }

        String jti = jwtService.extractJti(token); // USE JWT SERVICE
        UUID userId = jwtService.extractUserId(token); // USE JWT SERVICE
        long remainingTtl = jwtService.getRemainingTtl(token); // USE JWT SERVICE

        redisTemplate.opsForValue().set("revoked:" + jti, true, Duration.ofSeconds(remainingTtl));
        log.info("[TOKEN_REVOKED] Token added to blacklist | userId={} | jti={} | ttl={}s",
                userId, jti, remainingTtl);

        deleteUserSessions(userId);
        log.info("[LOGOUT_SUCCESS] Logout completed successfully | userId={} | jti={}", userId, jti);
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return authHeader.substring(7);
    }

    private void deleteUserSessions(UUID userId) {
        // Optional session cleanup
        log.debug("[SESSION_CLEANUP] User sessions cleaned up | userId={}", userId);
    }

    public boolean isTokenRevoked(String jti) {
        Boolean exists = redisTemplate.hasKey("revoked:" + jti);
        return Boolean.TRUE.equals(exists);
    }
}
