package com.algoshare.auth.domain.service;

import com.algoshare.auth.api.dto.OtpVerifyRequestDto;
import com.algoshare.auth.api.dto.OtpVerifyResponseDto;
import com.algoshare.auth.api.dto.OtpResendRequestDto;
import com.algoshare.auth.api.dto.OtpResendResponseDto;
import com.algoshare.auth.domain.exception.AccountLockedException;
import com.algoshare.auth.domain.exception.InvalidOtpException;
import com.algoshare.auth.domain.exception.SessionExpiredException;
import com.algoshare.auth.domain.exception.DeviceMismatchException;
import com.algoshare.auth.domain.model.Role;
import com.algoshare.auth.domain.model.TokenType;
import com.algoshare.auth.infrastructure.repository.UserRoleRepository;
import com.algoshare.auth.domain.model.SessionData;
import com.algoshare.auth.domain.utils.CryptoUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.algoshare.auth.domain.constants.AuthConstants.*;

/**
 * OTP Verification Service - Phase 2 of authentication
 * Verifies OTP and issues full ACCESS token
 */
@Service
@Slf4j
public class OtpVerificationService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final HttpServletRequest httpRequest;
    private final EventPublisherService eventPublisher;
    private final JwtService jwtService;
    private final UserRoleRepository userRoleRepository;
    private final CryptoUtils cryptoUtils;


    public OtpVerificationService(RedisTemplate<String, Object> redisTemplate,
                                  HttpServletRequest httpRequest,
                                  EventPublisherService eventPublisher,
                                  JwtService jwtService,
                                  UserRoleRepository userRoleRepository,
                                  CryptoUtils cryptoUtils) {
        this.redisTemplate = redisTemplate;
        this.httpRequest = httpRequest;
        this.eventPublisher = eventPublisher;
        this.jwtService = jwtService;
        this.userRoleRepository = userRoleRepository;
        this.cryptoUtils = cryptoUtils;
    }

    /**
     * Verify OTP and issue full ACCESS token
     */
    public OtpVerifyResponseDto verifyOtp(OtpVerifyRequestDto request) {
        log.info("[OTP_VERIFY_START] OTP verification initiated | sessionId={}", request.getSessionId());

        verifyPartialJwt(request.getPartialJwt());

        // 2. Get session from Redis
        SessionData session = (SessionData)
                redisTemplate.opsForValue().get(REDIS_SESSION_PREFIX + request.getSessionId());

        if (session == null) {
            log.warn("[SESSION_NOT_FOUND] Session expired or invalid | sessionId={}",
                    request.getSessionId());
            throw new SessionExpiredException("Session expired. Please login again.");
        }

        // 3. Calculate current device fingerprint and verify
        String currentFingerprint = cryptoUtils.calculateFingerprint(
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")
        );
        if (!currentFingerprint.equals(session.deviceFingerprint)) {
            log.warn("[DEVICE_MISMATCH] Device fingerprint mismatch | sessionId={} | userId={}",
                    request.getSessionId(), session.userId);
            throw new DeviceMismatchException("Device mismatch detected. Please login again.");
        }

        // 4. Get OTP hash from Redis
        String storedOtpHash = (String) redisTemplate.opsForValue()
                .get(REDIS_OTP_PREFIX + request.getSessionId());

        if (storedOtpHash == null) {
            log.warn("[OTP_EXPIRED] OTP expired | sessionId={}", request.getSessionId());
            throw new SessionExpiredException("OTP expired. Please request a new one.");
        }

        // 5. Verify OTP
        String providedOtpHash = cryptoUtils.hashOtp(request.getOtp());
        if (!cryptoUtils.slowEquals(providedOtpHash, storedOtpHash)) {
            // Increment attempts
            Long currentAttempts = redisTemplate.opsForValue().increment(REDIS_OTP_ATTEMPTS_PREFIX + request.getSessionId());

            if (currentAttempts != null && currentAttempts >= MAX_OTP_ATTEMPTS) {
                // GLOBAL LOCKOUT: Lock this user across the whole system
                String lockoutKey = REDIS_MFA_LOCKOUT_PREFIX + session.userId;
                redisTemplate.opsForValue().set(lockoutKey, "LOCKED", Duration.ofMinutes(30));

                log.warn("[MFA_LOCKOUT] Max OTP attempts reached. User locked globally | userId={} | sessionId={}",
                        session.userId, request.getSessionId());

                cleanupSession(request.getSessionId());
                throw new AccountLockedException("Too many failed attempts. Account locked for 30 minutes.", 1800);
            }

            log.warn("[OTP_INVALID] Invalid OTP | sessionId={} | attempts={}",
                    request.getSessionId(), currentAttempts);
            throw new InvalidOtpException("Invalid OTP. Please try again.");
        }

        log.info("[OTP_VERIFIED] OTP verified successfully | sessionId={} | userId={}",
                request.getSessionId(), session.userId);

        // 6. Generate full ACCESS token
        String accessToken = generateAccessToken(session.userId);

        String refreshToken = jwtService.generateRefreshToken(session.userId);

        // 7. Cleanup Redis (session, OTP, attempts, resend counter)
        cleanupSession(request.getSessionId());
        log.debug("[CLEANUP_COMPLETE] Session data cleaned up | sessionId={}", request.getSessionId());

        // 9. Build response
        OtpVerifyResponseDto response = new OtpVerifyResponseDto(
                accessToken,
                TokenType.ACCESS.toString(),
                refreshToken,
                (int) ACCESS_TOKEN_TTL.toSeconds()
        );

        log.info("[OTP_VERIFY_SUCCESS] OTP verification completed | userId={} | sessionId={}",
                session.userId, request.getSessionId());
        return response;
    }

    /**
     * Resend OTP with rate limiting
     */
    public OtpResendResponseDto resendOtp(OtpResendRequestDto request) {
        log.info("[OTP_RESEND_START] OTP resend requested | sessionId={}", request.getSessionId());

        // 1. Verify partial JWT
        verifyPartialJwt(request.getPartialJwt());

        // 2. Check session exists
        SessionData session = (SessionData)
                redisTemplate.opsForValue().get(REDIS_SESSION_PREFIX + request.getSessionId());

        if (session == null) {
            log.warn("[SESSION_NOT_FOUND] Session not found | sessionId={}", request.getSessionId());
            throw new SessionExpiredException("Session expired. Please login again.");
        }

        // 3. Check resend count
        Integer resendCount = (Integer) redisTemplate.opsForValue()
                .get(REDIS_OTP_RESEND_PREFIX + request.getSessionId());

        if (resendCount != null && resendCount >= MAX_RESEND_ATTEMPTS) {
            log.warn("[OTP_RESEND_LIMIT] Too many resend attempts | sessionId={} | count={}",
                    request.getSessionId(), resendCount);
            throw new RuntimeException("Maximum resend attempts reached. Please login again.");
        }

        // 4. Check cooldown
        Long lastSent = (Long) redisTemplate.opsForValue()
                .get(REDIS_OTP_LAST_SENT_PREFIX + request.getSessionId());

        if (lastSent != null) {
            long elapsed = System.currentTimeMillis() - lastSent;
            if (elapsed < RESEND_COOLDOWN.toMillis()) {
                long retryAfter = (RESEND_COOLDOWN.toMillis() - elapsed) / 1000;
                log.warn("[OTP_COOLDOWN] Resend in cooldown | sessionId={} | retryAfter={}s",
                        request.getSessionId(), retryAfter);
                throw new RuntimeException("Please wait " + retryAfter + " seconds before resending OTP");
            }
        }

        // 5. Generate new OTP
        String otp = cryptoUtils.generateOtp();
        String otpHash = cryptoUtils.hashOtp(otp);

        // 6. Update Redis
        redisTemplate.opsForValue().set(REDIS_OTP_PREFIX + request.getSessionId(), otpHash, Duration.ofMinutes(5));
        redisTemplate.opsForValue().set(REDIS_OTP_ATTEMPTS_PREFIX + request.getSessionId(), 0);
        redisTemplate.opsForValue().increment(REDIS_OTP_RESEND_PREFIX + request.getSessionId());
        redisTemplate.opsForValue().set(REDIS_OTP_LAST_SENT_PREFIX + request.getSessionId(),
                System.currentTimeMillis());

        try {
            eventPublisher.publishOtpRequested(session.userId, session.getEmail(), request.getSessionId());
            log.info("[OTP_EMAIL_TRIGGERED] OTP email event published | userId={} | sessionId={}",
                    session.userId, request.getSessionId());
        } catch (Exception e) {
            log.error("[OTP_EMAIL_FAILED] Failed to publish OTP event | userId={} | error={}",
                    session.userId, e.getMessage(), e);
            // Continue - don't fail login if event fails
        }

        int remaining = MAX_RESEND_ATTEMPTS - (resendCount != null ? resendCount + 1 : 1);

        OtpResendResponseDto response = new OtpResendResponseDto(
                "OTP resent successfully",
                remaining,
                (int) RESEND_COOLDOWN.toSeconds()
        );

        log.info("[OTP_RESEND_SUCCESS] OTP resent | sessionId={} | remaining={}",
                request.getSessionId(), remaining);
        return response;
    }

    private void verifyPartialJwt(String jwt) {
        try {
            JWTClaimsSet claims = jwtService.verifyAndParse(jwt);

            // Verify token type
            String tokenType = (String) claims.getClaim("tokenType");
            if (!"PRE_AUTH".equals(tokenType)) {
                throw new RuntimeException("Invalid token type. Expected PRE_AUTH");
            }

        } catch (Exception e) {
            log.error("[JWT_VERIFY_ERROR] Failed to verify partial JWT", e);
            throw new SessionExpiredException("Invalid or expired token. Please login again.");
        }
    }

    private String generateAccessToken(UUID userId) {
        // Get user roles from repository
        List<Role> roles = userRoleRepository.findRolesByUserId(userId);

        // TODO: Load permissions if needed
        List<String> permissions = List.of(); // Implement permission loading

        return jwtService.generateAccessToken(userId, roles, permissions);
    }

    private void cleanupSession(String sessionId) {
        redisTemplate.delete(REDIS_SESSION_PREFIX + sessionId);
        redisTemplate.delete(REDIS_OTP_PREFIX + sessionId);
        redisTemplate.delete(REDIS_OTP_ATTEMPTS_PREFIX + sessionId);
        redisTemplate.delete(REDIS_OTP_RESEND_PREFIX + sessionId);
        redisTemplate.delete(REDIS_OTP_LAST_SENT_PREFIX + sessionId);
    }
}
