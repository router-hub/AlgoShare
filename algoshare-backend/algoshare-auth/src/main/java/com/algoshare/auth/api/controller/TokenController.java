package com.algoshare.auth.api.controller;

import com.algoshare.auth.api.dto.TokenRefreshRequestDto;
import com.algoshare.auth.api.dto.TokenRefreshResponseDto;
import com.algoshare.auth.api.dto.TokenRevokeRequestDto;
import com.algoshare.auth.api.dto.TokenRevokeResponseDto;
import com.algoshare.auth.domain.service.JwtTokenService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Token Controller - Token Lifecycle Management
 * Handles token refresh and revocation.
 * 
 * Endpoints:
 * - POST /auth/token/refresh
 * - POST /auth/token/revoke
 */
@RestController
@RequestMapping("/auth/token")
@Tag(name = "Token Management", description = "Token refresh and revocation")
public class TokenController {

    private final JwtTokenService jwtTokenService;

    public TokenController(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    /**
     * Refresh ACCESS token using refresh token
     * Returns 200 OK with new ACCESS token and rotated refresh token
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenRefreshResponseDto> refreshToken(
            @Valid @RequestBody TokenRefreshRequestDto request) {
        TokenRefreshResponseDto response = jwtTokenService.refreshToken(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Revoke a token manually
     * Returns 200 OK with revocation timestamp
     */
    @PostMapping("/revoke")
    public ResponseEntity<TokenRevokeResponseDto> revokeToken(
            @Valid @RequestBody TokenRevokeRequestDto request) {
        TokenRevokeResponseDto response = jwtTokenService.revokeToken(request);
        return ResponseEntity.ok(response);
    }
}
