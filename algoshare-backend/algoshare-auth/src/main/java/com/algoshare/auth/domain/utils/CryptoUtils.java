package com.algoshare.auth.domain.utils;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class CryptoUtils {

    private static final String HASH_ALGORITHM = "SHA-256";
    private final SecureRandom secureRandom;

    public CryptoUtils(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /**
     * Generate 6-digit OTP
     */
    public String generateOtp() {
        int otp = 100000 + secureRandom.nextInt(900000);
        return String.valueOf(otp);
    }

    /**
     * Calculate device fingerprint hash
     */
    public String calculateFingerprint(String ip, String userAgent) {
        String raw = ip + "|" + (userAgent != null ? userAgent : "unknown");
        return hashWithSha256Base64(raw);
    }

    /**
     * Hash OTP for storage
     */
    public String hashOtp(String otp) {
        return hashWithSha256Base64(otp);
    }

    /**
     * Hash value to Hex (for Tokens)
     */
    public String hashToHex(String input) {
        return hashWithSha256Hex(input);
    }

    /**
     * Constant-time comparison to prevent timing attacks
     */
    public boolean slowEquals(String providedHash, String storedHash) {
        if (providedHash == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
            providedHash.getBytes(StandardCharsets.UTF_8),
            storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String hashWithSha256Base64(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String hashWithSha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
