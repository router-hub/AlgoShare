-- Token Bucket Rate Limiter (Lua Script)
-- Returns: {allowed (1/0), remaining_tokens, retry_after_seconds}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local tokens_requested = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5])

-- Get current state
local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

-- Initialize if first request
if tokens == nil then
    tokens = capacity
    last_refill = now
end

-- Refill tokens based on elapsed time
local time_passed = now - last_refill
local tokens_to_add = time_passed * refill_rate
tokens = math.min(capacity, tokens + tokens_to_add)

-- Check if request is allowed
local allowed = tokens >= tokens_requested
local remaining = tokens

if allowed then
    tokens = tokens - tokens_requested
    remaining = tokens
end

-- Update Redis
redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
redis.call('EXPIRE', key, ttl)

-- Calculate retry_after
local retry_after = 0
if not allowed then
    local deficit = tokens_requested - tokens
    retry_after = math.ceil(deficit / refill_rate)
end

return {allowed and 1 or 0, math.floor(remaining), retry_after}
