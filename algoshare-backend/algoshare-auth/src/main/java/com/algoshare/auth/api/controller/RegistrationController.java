package com.algoshare.auth.api.controller;

import com.algoshare.auth.api.dto.RegistrationRequestDto;
import com.algoshare.auth.api.dto.RegistrationResponseDto;
import com.algoshare.auth.api.dto.VerifyEmailRequestDto;
import com.algoshare.auth.api.dto.VerifyEmailResponseDto;
import com.algoshare.auth.domain.service.EmailVerificationService;
import com.algoshare.auth.domain.service.RegistrationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Registration Controller - Identity Creation
 * Handles user registration and email verification.
 * 
 * Endpoints:
 * - POST /auth/register
 * - POST /auth/verify-email
 * - POST /auth/resend-verification
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "Registration", description = "User registration and email verification")
public class RegistrationController {

    private final RegistrationService registrationService;
    private final EmailVerificationService emailVerificationService;

    public RegistrationController(RegistrationService registrationService, EmailVerificationService emailVerificationService){
        this.registrationService = registrationService;
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * Register a new user account
     * Returns 201 Created with userId and PENDING_EMAIL_VERIFICATION status
     */
    @PostMapping("/register")
    public ResponseEntity<RegistrationResponseDto> register(
            @Valid @RequestBody RegistrationRequestDto request) {
        RegistrationResponseDto response = this.registrationService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    /**
     * Verify email with token from email link
     * Returns 200 OK with EMAIL_VERIFIED status
     */
    @PostMapping("/verify-email")
    public ResponseEntity<VerifyEmailResponseDto> verifyEmail(
            @Valid @RequestBody VerifyEmailRequestDto request) {
        VerifyEmailResponseDto response = emailVerificationService.verifyEmail(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Resend verification email
     * Returns 200 OK
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@RequestParam String email) {
        emailVerificationService.resendVerification(email);
        return ResponseEntity.ok().build();
    }
}
