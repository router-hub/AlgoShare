# AlgoShare Authentication Complete Flow Diagrams

This folder contains the complete authentication flow broken down into separate, readable diagrams.

## 📋 Diagram Files

### 1️⃣ [User Registration Flow](./1-user-registration.mmd)
- User registration form submission
- Email validation and password hashing
- Verification token generation (AES-256-GCM)
- Kafka event publishing
- Async email verification sending via AWS SES

### 2️⃣ [Email Verification Flow](./2-email-verification.mmd)
- Email link verification
- Token decryption and validation
- User status update to EMAIL_VERIFIED
- Redirect to login page

### 3️⃣ [Login - Password Step](./3-login-password.mmd)
- Email/password submission
- BCrypt password verification
- Partial JWT generation (mfa=false)
- OTP generation and email sending
- Session management in Redis

### 4️⃣ [Login - OTP Verification](./4-otp-verification.mmd)
- OTP submission and validation
- JWT signature verification
- **Device fingerprint validation** (IP + User-Agent)
- Session and OTP matching
- Full JWT generation with roles/permissions
- Login audit logging
- **Enhanced JWT claims**: aud, token_type=ACCESS

### 4️⃣b [OTP Resend Policy](./4b-otp-resend.mmd)
- **Max 3 resends** per login attempt
- **60-second cooldown** between resends
- Rate limiting (5 requests per 5 minutes)
- Prevents email spam abuse
- Timestamp tracking

### 5️⃣ [Encrypted Token Management](./5-token-management.mmd)
- AES-256-GCM encryption layer
- AWS KMS master key rotation
- Verification token structure
- OTP storage encryption

### 6️⃣ [Gateway Rate Limiting](./6-rate-limiting.mmd)
- Redis key structure for rate limits
- Token bucket state management
- Different limits per endpoint:
  - Registration: 20/hour per IP
  - Login: 60/10min per IP
  - OTP Verify: 10/5min per session
  - OTP Resend: 5/5min per session

## 🔒 Security Features

See **[SECURITY.md](./SECURITY.md)** for comprehensive security documentation including:

### Production-Grade Security Measures:
- ✅ **Device Fingerprinting**: IP + User-Agent hashing to prevent token replay
- ✅ **OTP Resend Limits**: Max 3 resends with 60s cooldown
- ✅ **JWT Audience Validation**: `aud` claim prevents cross-service token reuse
- ✅ **Token Type Distinction**: `PRE_AUTH` vs `ACCESS` tokens
- ✅ **Progressive Login Delays**: Exponential backoff (2^n seconds)
- ✅ **Account Lockout**: 15-minute lock after 5 failed attempts
- ✅ **Multi-Layer Rate Limiting**: IP-based + Account-based

## 🎯 Why Split?

The original `algoshare-auth-complete-flow.mmd` contained all 6 diagrams in a single file, making it:
- ❌ Difficult to read in diagram viewers
- ❌ Hard to navigate and find specific flows
- ❌ Overwhelming for code reviews
- ❌ Challenging to render in documentation

By splitting into separate files:
- ✅ Each diagram is focused and readable
- ✅ Easy to reference specific flows
- ✅ Better for documentation and presentations
- ✅ Faster rendering in Mermaid viewers

## 🔗 Related Documentation

- Main auth configuration: `algoshare-backend/algoshare-auth/src/main/resources/application.yml`
- Gateway rate limiting: `algoshare-backend/algoshare-gateway/src/main/java/com/algoshare/gateway/filter/AlgoShareRateLimitFilterFactory.java`
- JWT configuration: `algoshare-backend/algoshare-auth/src/main/java/com/algoshare/auth/config/RsaKeyConfig.java`

## 📊 View Diagrams

You can view these Mermaid diagrams using:
- **VS Code**: Mermaid Preview extension
- **GitHub**: Native Mermaid rendering in markdown
- **Online**: [Mermaid Live Editor](https://mermaid.live/)
- **IntelliJ IDEA**: Mermaid plugin
