-- Create email verification tokens table
-- Stores SHA-256 hash of verification tokens (not raw tokens)
-- Raw tokens sent via email, hashes stored for security

CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,  -- SHA-256 produces 64-char hex
    user_id UUID NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Index for fast token lookups
CREATE INDEX idx_token_hash ON email_verification_tokens(token_hash);

-- Index for cleanup queries (find expired tokens)
CREATE INDEX idx_expires_at ON email_verification_tokens(expires_at);

-- Index for user-based queries
CREATE INDEX idx_user_id ON email_verification_tokens(user_id);

COMMENT ON TABLE email_verification_tokens IS 'Email verification tokens with SHA-256 hashes';
COMMENT ON COLUMN email_verification_tokens.token_hash IS 'SHA-256 hash of 256-bit random token';
COMMENT ON COLUMN email_verification_tokens.user_id IS 'User this token belongs to';
COMMENT ON COLUMN email_verification_tokens.expires_at IS 'Token expiry time (24 hours from creation)';
