package com.algoshare.auth.domain.constants;

import java.time.Duration;

public final class AuthConstants {

    // Private constructor prevents instantiation
    private AuthConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static final String HASH_ALGORITHM = "SHA-256";
    public static final int TOKEN_BYTE_LENGTH = 32;
    public static final int EXPIRATION_HOURS = 24;
    public static final int BCRYPT_COST_FACTOR = 12;
    public static final String USER_EVENTS_TOPIC = "user.events";

    public static final int MAX_LOGIN_ATTEMPTS = 5;
    public static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
    public static final Duration SESSION_TTL = Duration.ofMinutes(5);
    public static final Duration OTP_TTL = Duration.ofMinutes(5);

    public static final int MAX_OTP_ATTEMPTS = 3;
    public static final int MAX_RESEND_ATTEMPTS = 3;
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofDays(7);

    // Redis Key Prefixes
    public static final String REDIS_SESSION_PREFIX = "auth:session:";
    public static final String REDIS_OTP_PREFIX = "auth:otp:";
    public static final String REDIS_OTP_ATTEMPTS_PREFIX = "auth:otp:attempts:";
    public static final String REDIS_OTP_RESEND_PREFIX = "auth:otp:resend:";
    public static final String REDIS_OTP_LAST_SENT_PREFIX = "auth:otp:last_sent:";
    public static final String REDIS_LOGIN_ATTEMPTS_PREFIX = "auth:login:attempts:";
    public static final String REDIS_MFA_LOCKOUT_PREFIX = "auth:lockout:mfa:";
    public static final String REDIS_TOKEN_REVOKED_PREFIX = "auth:token:revoked:";

}
