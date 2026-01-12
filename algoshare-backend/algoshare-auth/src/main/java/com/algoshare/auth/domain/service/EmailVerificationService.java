package com.algoshare.auth.domain.service;

import com.algoshare.auth.api.dto.VerifyEmailRequestDto;
import com.algoshare.auth.api.dto.VerifyEmailResponseDto;
import com.algoshare.auth.domain.exception.TokenExpiredException;
import com.algoshare.auth.domain.model.AccountStatus;
import com.algoshare.auth.infrastructure.entity.EmailVerificationTokenEntity;
import com.algoshare.auth.infrastructure.entity.UserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Email Verification Service - Handles email verification flow
 * Validates token and activates user account
 */
@Service
@Slf4j
public class EmailVerificationService {

    private final TokenService tokenService;
    private final UserService userService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final EventPublisherService eventPublisherService;


    public EmailVerificationService(TokenService tokenService, UserService userService,
                                    RedisTemplate<String, Object> redisTemplate,
                                    EventPublisherService eventPublisherService) {
        this.tokenService = tokenService;
        this.userService = userService;
        this.redisTemplate = redisTemplate;
        this.eventPublisherService = eventPublisherService;
    }

    /**
     * Verify email using token from email link
     *
     * @param request Contains verification token
     * @return Response with verified status
     * @throws TokenExpiredException if token is invalid or expired
     */
    @Transactional
    public VerifyEmailResponseDto verifyEmail(VerifyEmailRequestDto request) {
        log.info("[VERIFY_EMAIL_START] Email verification initiated | token={}",
                request.getToken().substring(0, 8) + "...");

        // 1. Validate token (hash and check DB)
        EmailVerificationTokenEntity token = tokenService.validateToken(request.getToken());
        if (token == null) {
            log.warn("[TOKEN_INVALID] Token validation failed");
            throw new TokenExpiredException("Invalid or expired verification token");
        }

        // 2. Update user status to EMAIL_VERIFIED
        UserEntity user = userService.updateStatus(token.getUserId(), AccountStatus.ACTIVE.toString());
        log.info("[EMAIL_VERIFIED] User email verified | userId={} | email={}",
                user.getId(), user.getEmail());

        // 3. Delete verification token (cleanup)
        tokenService.deleteTokensByUserId(user.getId());
        log.debug("[TOKEN_CLEANUP] Verification tokens deleted | userId={}", user.getId());

        // 4. Build response
        VerifyEmailResponseDto response = new VerifyEmailResponseDto(
                user.getId().toString(),
                user.getEmail(),
                "EMAIL_VERIFIED",
                java.time.Instant.now()
        );

        log.info("[VERIFY_EMAIL_SUCCESS] Email verification completed | userId={} | email={}",
                user.getId(), user.getEmail());
        return response;
    }

    /**
     * Resend verification email
     *
     * @param email User's email
     * @throws Exception if user not found or rate limited
     */
    @Transactional
    public void resendVerification(String email) {
        log.info("[RESEND_VERIFICATION_START] Resending verification email | email={}", email);

        UserEntity user = userService.findByEmail(email);

        if (user.isEmailVerified()) {
            log.warn("[ALREADY_VERIFIED] User email already verified | email={}", email);
            throw new RuntimeException("Email already verified");
        }

        // ADD RATE LIMITING
        String rateLimitKey = "verification:resend:" + user.getId();
        Long resendCount = redisTemplate.opsForValue().increment(rateLimitKey);

        if (resendCount == 1) {
            redisTemplate.expire(rateLimitKey, Duration.ofHours(1));
        }

        if (resendCount > 3) { // Max 3 resends per hour
            log.warn("[RATE_LIMIT] Too many verification resend attempts | userId={}", user.getId());
            throw new RuntimeException("Too many resend attempts. Please try again later.");
        }

        tokenService.deleteTokensByUserId(user.getId());
        String rawToken = tokenService.generateRawToken();
        tokenService.createVerificationToken(user.getId(), rawToken);

        // Publish event
        eventPublisherService.publishUserRegistered(user.getId(), email, rawToken);

        log.info("[RESEND_VERIFICATION_SUCCESS] Verification email resent | userId={} | email={}",
                user.getId(), email);
    }

}
