package com.algoshare.auth.api.controller;

import com.algoshare.auth.api.dto.*;
import com.algoshare.auth.domain.service.LoginService;
import com.algoshare.auth.domain.service.LogoutService;
import com.algoshare.auth.domain.service.OtpVerificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Login Controller - Identity Verification + MFA
 * Handles password authentication, OTP verification, and session management.
 * 
 * Endpoints:
 * - POST /auth/login
 * - POST /auth/otp/verify
 * - POST /auth/otp/resend
 * - POST /auth/logout
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "Login, MFA, and session management")
public class LoginController {
    private final LoginService loginService;
    private final OtpVerificationService otpVerificationService;
    private final LogoutService logoutService;

    public LoginController(LoginService loginService, OtpVerificationService otpVerificationService, LogoutService logoutService) {
        this.loginService = loginService;
        this.otpVerificationService = otpVerificationService;
        this.logoutService = logoutService;
    }

    /**
     * Step 1: Password verification
     * Returns 200 OK with partial JWT (PRE_AUTH token) and sessionId
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(@Valid @RequestBody LoginRequestDto request) {
        LoginResponseDto response = loginService.login(request);
        return ResponseEntity.ok(response);
    }


    /**
     * Step 2: OTP verification
     * Returns 200 OK with full ACCESS token
     */
    @PostMapping("/otp/verify")
    public ResponseEntity<OtpVerifyResponseDto> verifyOtp(
            @Valid @RequestBody OtpVerifyRequestDto request) {
        OtpVerifyResponseDto response = otpVerificationService.verifyOtp(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Resend OTP
     * Returns 200 OK with remaining resends and cooldown
     */
    @PostMapping("/otp/resend")
    public ResponseEntity<OtpResendResponseDto> resendOtp(
            @Valid @RequestBody OtpResendRequestDto request) {
        OtpResendResponseDto response = otpVerificationService.resendOtp(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Logout - revoke current session
     * Returns 200 OK
     */
    /**
     * Logout - revoke current session
     * Returns 204 No Content on success
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader("Authorization") String authorizationHeader) {
        logoutService.logout(authorizationHeader);
        return ResponseEntity.noContent().build();
    }
}
