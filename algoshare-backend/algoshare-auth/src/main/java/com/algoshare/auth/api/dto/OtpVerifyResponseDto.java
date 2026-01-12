package com.algoshare.auth.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OtpVerifyResponseDto {

    private String token;         // Full ACCESS token
    private String tokenType;     // ACCESS
    private String refreshToken;
    private int expiresIn;        // 604800 seconds (7 days)
}
