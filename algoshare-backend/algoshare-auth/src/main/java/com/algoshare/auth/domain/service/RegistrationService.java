package com.algoshare.auth.domain.service;

import com.algoshare.auth.api.dto.RegistrationRequestDto;
import com.algoshare.auth.api.dto.RegistrationResponseDto;
import com.algoshare.auth.domain.exception.EmailAlreadyExistsException;
import com.algoshare.auth.domain.exception.InvalidInputException;
import com.algoshare.auth.domain.model.AccountStatus;
import com.algoshare.auth.infrastructure.entity.UserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration Service - Orchestrates user registration flow
 * 
 * Flow:
 * 1. Validate input
 * 2. Check email doesn't exist
 * 3. Create user with hashed password
 * 4. Generate verification token (256-bit random → SHA-256 hash)
 * 5. Publish USER_REGISTERED event (async)
 * 6. Return response
 * 
 * Transaction: Steps 1-4 are transactional
 * Event publishing (step 5) is async and doesn't block/fail registration
 */
@Service
@Slf4j
public class RegistrationService {

    private final UserService userService;
    private final TokenService tokenService;
    private final EventPublisherService eventPublisher;

    public RegistrationService(
            UserService userService,
            TokenService tokenService,
            EventPublisherService eventPublisher) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Register new user
     * 
     * @param request Registration details (email, password, name)
     * @return Registration response with userId and status
     * @throws EmailAlreadyExistsException if email already registered
     * @throws InvalidInputException if passwords don't match
     */
    @Transactional
    public RegistrationResponseDto register(RegistrationRequestDto request) {
        log.info("[REGISTER_START] User registration initiated | email={}", request.getEmail());

        // 1. Validate passwords match
        validatePasswordMatch(request);

        // 2. Check email doesn't exist
        if (userService.existsByEmail(request.getEmail())) {
            log.warn("[EMAIL_EXISTS] Registration failed - email already exists | email={}", 
                request.getEmail());
            throw new EmailAlreadyExistsException("User already registered with email: " + request.getEmail());
        }

        // 3. Create user with hashed password
        UserEntity user = userService.createUser(request);
        log.info("[USER_CREATED] User created successfully | userId={} | email={} | status={}", 
            user.getId(), user.getEmail(), user.getStatus());

        // 4. Generate verification token
        String rawToken = tokenService.generateRawToken();
        tokenService.createVerificationToken(user.getId(), rawToken);
        log.info("[TOKEN_GENERATED] Verification token created | userId={} | expiresIn={}h", 
            user.getId(), 24);

        // 5. Publish event (async, non-blocking)
        try {
            eventPublisher.publishUserRegistered(user.getId(), user.getEmail(), rawToken);
            log.info("[EVENT_TRIGGERED] USER_REGISTERED event publishing initiated | userId={}", 
                user.getId());
        } catch (Exception e) {
            // Don't fail registration if event publishing fails
            log.error("[EVENT_FAILED] Event publishing failed but registration succeeded | userId={} | email={} | error={}", 
                user.getId(), user.getEmail(), e.getMessage(), e);
        }

        // 6. Build response
        RegistrationResponseDto response = new RegistrationResponseDto(
            user.getId().toString(),
            user.getEmail(),
            AccountStatus.PENDING_EMAIL_VERIFICATION.toString()
        );

        log.info("[REGISTER_SUCCESS] Registration completed successfully | userId={} | email={} | status={}", 
            user.getId(), user.getEmail(), response.getStatus());

        return response;
    }

    /**
     * Validate password and confirmPassword match
     */
    private void validatePasswordMatch(RegistrationRequestDto request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            log.warn("[VALIDATION_FAILED] Password mismatch | email={}", request.getEmail());
            throw new InvalidInputException("Password and confirm password do not match");
        }
        log.debug("[VALIDATION_PASSED] Password validation successful | email={}", request.getEmail());
    }
}
