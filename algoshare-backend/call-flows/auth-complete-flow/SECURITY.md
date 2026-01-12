# AlgoShare Auth Security Enhancements - Production Grade

## 🔒 Security Improvements Implemented

### 1️⃣ Device Fingerprinting & Session Binding

**Problem**: Token replay attacks - attacker steals JWT and uses from different device

**Solution**: Bind session to device fingerprint

```redis
session:{sessionId} → {
  userId: "uuid-123",
  email: "user@example.com",
  ipHash: "SHA256(192.168.1.100)",      # Device IP hash
  uaHash: "SHA256(Mozilla/5.0...)",    # User-Agent hash
  createdAt: 1736592533,
  TTL: 300  # 5 minutes
}
```

**Validation Flow:**
```
Login (Password Step):
├─ Calculate: ipHash = SHA256(request.ip)
├─ Calculate: uaHash = SHA256(request.userAgent)
└─ Store in session

OTP Verify:
├─ Calculate current fingerprint
├─ Fetch session fingerprint
├─ Compare: current.ipHash == session.ipHash
├─ Compare: current.uaHash == session.uaHash
└─ Reject if mismatch (401 Device mismatch)
```

**Benefits:**
- ✅ Prevents token replay from different devices
- ✅ Detects stolen tokens
- ✅ Soft check (SHA256 hashes, not exact values for privacy)

---

### 2️⃣ OTP Resend Policy

**Problem**: Attackers spam OTP emails, causing:
- Email quota exhaustion
- User annoyance
- Potential DOS

**Solution**: Multi-layer resend limits

```redis
# Per-session resend counter
otp_resend:{sessionId} → count: 2
  TTL: 300  # 5 minutes (same as session)

# Last OTP sent timestamp
last_otp_sent:{sessionId} → timestamp: 1736592533
  TTL: 300  # 5 minutes
```

**Resend Rules:**
```
1. Max resends: 3 per login attempt
2. Cooldown: 60 seconds between resends
3. Global rate limit: 5 requests per 5 minutes (Gateway)
```

**Flow:**
```
POST /auth/otp/resend
  ↓
Check resend count
  ├─ count >= 3 → 429 Max resends exceeded
  └─ count < 3 → Continue
  ↓
Check last sent time
  ├─ < 60s ago → 429 Retry-After: X
  └─ >= 60s → Generate new OTP
  ↓
INCR otp_resend:{sessionId}
SET last_otp_sent:{sessionId} = NOW()
Send email
```

**Benefits:**
- ✅ Prevents email spam
- ✅ Protects email quota
- ✅ Forces attacker to restart login (limits efficiency)

---

### 3️⃣ JWT Audience (aud) Claim

**Problem**: Token reuse across services - token meant for Gateway used for direct service access

**Solution**: Add `aud` (audience) claim

**PRE_AUTH Token (Partial JWT):**
```json
{
  "sub": "user-uuid-123",
  "email": "user@example.com",
  "aud": "algoshare-gateway",       ← Only Gateway can accept
  "token_type": "PRE_AUTH",          ← Pre-authentication token
  "mfa": false,
  "sessionId": "session-xyz",
  "exp": 1736592833,                 ← 5 minutes
  "iss": "algoshare-auth",
  "jti": "token-abc-123"
}
```

**ACCESS Token (Full JWT):**
```json
{
  "sub": "user-uuid-123",
  "email": "user@example.com",
  "aud": "algoshare-gateway",       ← Audience validation
  "token_type": "ACCESS",            ← Full access token
  "mfa": true,
  "roles": ["ROLE_USER"],
  "permissions": ["trade:execute"],
  "exp": 1737197633,                 ← 7 days
  "iss": "algoshare-auth",
  "jti": "token-def-456"
}
```

**Validation:**
```java
// Gateway/Service must validate aud claim
if (!claims.getAudience().equals("algoshare-gateway")) {
    throw new InvalidTokenException("Token not intended for this service");
}
```

**Benefits:**
- ✅ Prevents token reuse across services
- ✅ Limits blast radius of stolen tokens
- ✅ JWT best practice (RFC 7519)

---

### 4️⃣ Token Type Distinction

**Problem**: Ambiguous token usage - hard to distinguish PRE_AUTH from ACCESS tokens

**Solution**: Explicit `token_type` claim

**Token Types:**
```
PRE_AUTH:
├─ Purpose: OTP verification only
├─ Lifetime: 5 minutes
├─ MFA: false
└─ Permissions: None

ACCESS:
├─ Purpose: API access
├─ Lifetime: 7 days  
├─ MFA: true
└─ Permissions: roles + permissions
```

**Benefits:**
- ✅ Clearer debugging (logs show token type)
- ✅ Safer authorization checks
- ✅ Prevents accidental misuse
- ✅ Better audit trails

---

## 📊 Redis Key Structure (Complete)

```redis
# Login attempt tracking (brute force protection)
login_attempts:{email} → count: 3
  TTL: 900  # 15 minutes sliding window

# Account lockout
login_lock:{email} → { lockUntil: timestamp }
  TTL: 900  # 15 minutes

# Session metadata (device binding)
session:{sessionId} → {
  userId, email, ipHash, uaHash, createdAt
}
  TTL: 300  # 5 minutes

# OTP storage
otp:{sessionId} → encrypted_otp
  TTL: 300  # 5 minutes

# OTP resend tracking
otp_resend:{sessionId} → count: 2
  TTL: 300  # 5 minutes

# Last OTP sent (cooldown)
last_otp_sent:{sessionId} → timestamp
  TTL: 300  # 5 minutes

# OTP attempts tracking
attempts:{sessionId} → count: 1
  TTL: 300  # 5 minutes

# Token JTI (revocation support)
token:{jti} → { userId, issuedAt, tokenType }
  TTL: 604800  # 7 days
```

---

## 🛡️ Attack Scenarios & Mitigations

### **Attack 1: Token Replay from Different Device**
```
Attacker steals PRE_AUTH token
  ↓
Tries to verify OTP from attacker's device
  ↓
System calculates fingerprint
  ├─ ipHash: SHA256(attacker_ip) ≠ session.ipHash ❌
  └─ uaHash: SHA256(attacker_ua) ≠ session.uaHash ❌
  ↓
Reject: 401 Device mismatch
```

### **Attack 2: OTP Email Spam**
```
Attacker calls /auth/otp/resend repeatedly
  ↓
Request 1: Send OTP ✅
Request 2 (10s later): 429 Retry-After: 50 ❌
Request 3 (after 60s): Send OTP ✅
Request 4 (after 60s): Send OTP ✅
Request 5: 429 Max resends exceeded ❌
```

### **Attack 3: Token Reuse Across Services**
```
Attacker steals ACCESS token
  ↓
Tries to use directly on microservice (bypassing gateway)
  ↓
Microservice validates aud claim
  ↓
aud = "algoshare-gateway" ≠ "algoshare-order-service" ❌
  ↓
Reject: 401 Invalid audience
```

### **Attack 4: PRE_AUTH Token Misuse**
```
Attacker tries to call /api/order/execute with PRE_AUTH token
  ↓
Gateway validates token_type claim
  ↓
token_type = "PRE_AUTH" ≠ "ACCESS" ❌
  ↓
Reject: 403 Insufficient authentication
```

---

## 🎯 Production Checklist

### **Implemented ✅**
- [x] Device fingerprinting (IP + User-Agent hashing)
- [x] OTP resend limits (max 3, 60s cooldown)
- [x] JWT aud claim validation
- [x] JWT token_type distinction (PRE_AUTH vs ACCESS)
- [x] Progressive login delays (exponential backoff)
- [x] Account lockout after 5 failures
- [x] Failed attempt tracking and audit logging

### **Recommended Enhancements 🔧**
- [ ] **Refresh Token Rotation**: Short-lived access tokens + refresh tokens
- [ ] **CAPTCHA Trigger**: After 3 failed OTP attempts
- [ ] **Geo-Velocity Checks**: Detect impossible travel (NYC → Tokyo in 1 hour)
- [ ] **Hardware Token Support**: TOTP/WebAuthn for high-value accounts
- [ ] **Push Notification OTP**: Alternative to email OTP
- [ ] **Behavioral Biometrics**: Typing patterns, mouse movements

---

## 📚 References

- **JWT Best Practices**: [RFC 7519 - JSON Web Token](https://datatracker.ietf.org/doc/html/rfc7519)
- **Device Fingerprinting**: [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
- **Rate Limiting**: [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- **Progressive Delays**: [Exponential Backoff Strategy (AWS)](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)
