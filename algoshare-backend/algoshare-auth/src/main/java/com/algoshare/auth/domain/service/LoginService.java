package com.algoshare.auth.domain.service;

import com.algoshare.auth.api.dto.LoginRequestDto;
import com.algoshare.auth.api.dto.LoginResponseDto;
import com.algoshare.auth.domain.exception.AccountLockedException;
import com.algoshare.auth.domain.exception.InvalidCredentialsException;
import com.algoshare.auth.domain.model.AccountStatus;
import com.algoshare.auth.infrastructure.entity.UserEntity;
import com.algoshare.auth.domain.model.SessionData;
import com.algoshare.auth.domain.utils.CryptoUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static com.algoshare.auth.domain.constants.AuthConstants.*;

/**
 * Login Service - Handles Phase 1 of authentication (password + OTP generation)
 * Phase 1: Password verification → PRE_AUTH token + OTP
 */
@Service
@Slf4j
public class LoginService {

    private final UserService userService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final EventPublisherService eventPublisher;
    private final HttpServletRequest httpRequest;
    private final JwtService jwtService;
    private final CryptoUtils cryptoUtils;


    public LoginService(UserService userService,
                        RedisTemplate<String, Object> redisTemplate,
                        EventPublisherService eventPublisher,
                        HttpServletRequest httpRequest,
                        JwtService jwtService,
                        CryptoUtils cryptoUtils) {
        this.userService = userService;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.httpRequest = httpRequest;
        this.jwtService = jwtService;
        this.cryptoUtils = cryptoUtils;
    }

    /**
     * Phase 1: Password authentication
     * Returns PRE_AUTH token and sessionId for OTP verification
     */
    public LoginResponseDto login(LoginRequestDto request) {
        log.info("[LOGIN_START] Login attempt | email={}", request.getEmail());

        // 1. Check account lockout
        checkAccountLockout(request.getEmail());

        // 2. Find user & 3. Verify password (merged to prevent enumeration)
        UserEntity user = null;
        try {
            user = userService.findByEmail(request.getEmail());
        } catch (Exception e) {
            // User not found - continue to dummy check
        }

        // NEW: Check if the user is locked out from a previous MFA failure
        if (user != null) {
            String mfaLockoutKey = REDIS_MFA_LOCKOUT_PREFIX + user.getId();
            if (Boolean.TRUE.equals(redisTemplate.hasKey(mfaLockoutKey))) {
                Long ttl = redisTemplate.getExpire(mfaLockoutKey);
                log.warn("[MFA_LOCK_ACTIVE] Login blocked by MFA lockout | userId={}", user.getId());
                throw new AccountLockedException("Account locked due to previous MFA failures.", 
                        ttl != null ? ttl.intValue() : 1800);
            }
        }

        if (user == null) {
            // Dummy check to prevent timing attacks
            // Encode a dummy password to simulate work
            userService.verifyPassword(request.getPassword(), "$2a$12$DummySaltToWasteTime......");
            handleFailedLogin(request.getEmail());
            log.warn("[LOGIN_FAILED] User not found (timing protected) | email={}", request.getEmail());
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // Verify password
        if (!userService.verifyPassword(request.getPassword(), user.getPasswordHash())) {
            handleFailedLogin(request.getEmail());
            log.warn("[LOGIN_FAILED] Invalid credentials | email={}", request.getEmail());
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // 4. Check email verification status
        if (!user.isEmailVerified()) {
            log.warn("[EMAIL_NOT_VERIFIED] Login attempt with unverified email | email={}",
                    request.getEmail());
            throw new InvalidCredentialsException("Email not verified. Please verify your email first.");
        }

        // 5. Check account status
        if (!AccountStatus.ACTIVE.toString().equals(user.getStatus())) {
            log.warn("[ACCOUNT_INACTIVE] Login attempt with inactive account | email={} | status={}",
                    request.getEmail(), user.getStatus());
            throw new InvalidCredentialsException("Account is not active. Status: " + user.getStatus());
        }

        // 6. Reset failed attempts on successful password verification
        redisTemplate.delete(REDIS_LOGIN_ATTEMPTS_PREFIX + request.getEmail());

        // 7. Calculate device fingerprint
        String deviceFingerprint = cryptoUtils.calculateFingerprint(
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")
        );

        // 8. Generate sessionId
        String sessionId = UUID.randomUUID().toString();

        // 9. Store session in Redis
        SessionData sessionData = new SessionData(user.getId(), user.getEmail(), deviceFingerprint);
        redisTemplate.opsForValue().set(REDIS_SESSION_PREFIX + sessionId, sessionData, SESSION_TTL);
        log.debug("[SESSION_CREATED] Session stored in Redis | sessionId={} | userId={}",
                sessionId, user.getId());

        // 10. Generate 6-digit OTP
        String otp = cryptoUtils.generateOtp();

        // 11. Store OTP in Redis (encrypted/hashed for security)
        String otpHash = cryptoUtils.hashOtp(otp);
        redisTemplate.opsForValue().set(REDIS_OTP_PREFIX + sessionId, otpHash, OTP_TTL);
        redisTemplate.opsForValue().set(REDIS_OTP_ATTEMPTS_PREFIX + sessionId, 0, OTP_TTL);
        log.debug("[OTP_GENERATED] OTP stored in Redis | sessionId={}", sessionId);

        // 12. Generate partial JWT (PRE_AUTH token)
        String partialJwt = generatePreAuthToken(user.getId(), sessionId);

        // 13. Publish Kafka event for OTP email
        try {
            eventPublisher.publishOtpRequested(user.getId(), user.getEmail(), sessionId);
            log.info("[OTP_EMAIL_TRIGGERED] OTP email event published | userId={} | sessionId={}",
                    user.getId(), sessionId);
        } catch (Exception e) {
            log.error("[OTP_EMAIL_FAILED] Failed to publish OTP event | userId={} | error={}",
                    user.getId(), e.getMessage(), e);
            // Continue - don't fail login if event fails
        }

        // 14. Build response
        LoginResponseDto response = new LoginResponseDto(
                AccountStatus.PENDING_MFA.toString(),
                partialJwt,
                sessionId,
                (int) SESSION_TTL.toSeconds()
        );

        log.info("[LOGIN_SUCCESS] Login phase 1 completed | userId={} | email={} | sessionId={}",
                user.getId(), request.getEmail(), sessionId);
        return response;
    }

    /**
     * Check if account is locked due to failed attempts
     */
    private void checkAccountLockout(String email) {
        String key = REDIS_LOGIN_ATTEMPTS_PREFIX + email;
        Integer attempts = (Integer) redisTemplate.opsForValue().get(key);

        if (attempts != null && attempts >= MAX_LOGIN_ATTEMPTS) {
            Long ttl = redisTemplate.getExpire(key);
            log.warn("[ACCOUNT_LOCKED] Login blocked - too many failed attempts | email={} | retryAfter={}s",
                    email, ttl);
            throw new AccountLockedException("Account locked due to too many failed login attempts",
                    ttl != null ? ttl.intValue() : 900);
        }
    }

    /**
     * Handle failed login attempt - increment counter
     */
    private void handleFailedLogin(String email) {
        String key = REDIS_LOGIN_ATTEMPTS_PREFIX + email;
        Long attempts = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, LOCKOUT_DURATION);

        log.warn("[LOGIN_ATTEMPT_FAILED] Failed login attempt | email={} | attempts={}/{}",
                email, attempts, MAX_LOGIN_ATTEMPTS);
    }

    /**
     * Generate PRE_AUTH JWT token
     */
    private String generatePreAuthToken(UUID userId, String sessionId) {
        return jwtService.generatePreAuthToken(userId, sessionId);
    }
}
