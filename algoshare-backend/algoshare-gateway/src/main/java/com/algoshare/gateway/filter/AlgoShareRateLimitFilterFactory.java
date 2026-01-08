package com.algoshare.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * REDIS-BASED RATE LIMITER - Distributed Token Bucket Implementation for AlgoShare Gateway
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🎯 WHAT THIS CLASS DOES                                                                         │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * This class protects our API Gateway from being overwhelmed by limiting how many requests
 * a client (user/IP/API key) can make within a time period.
 *
 * Think of it like a bouncer at a club:
 *   - Each person gets 100 tokens (capacity)
 *   - Each request costs 1 token
 *   - Tokens refill over time (1.66 per second = 100 per minute)
 *   - When you run out of tokens → 429 Too Many Requests
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🏭 WHY IS IT CALLED A "FACTORY"?                                                                │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * The Factory Pattern lets Spring Cloud Gateway create DIFFERENT rate limiters for DIFFERENT routes.
 *
 * Without Factory (hardcoded):
 *     RateLimiter limiter = new RateLimiter(100, 10);  // Same for ALL routes!
 *
 * With Factory (configurable per route):
 *     // In application.yml:
 *     routes:
 *       - id: payment-route
 *         filters:
 *           - RedisRateLimit=payment,100,1.66    ← Strict limits
 *       - id: health-check-route
 *         filters:
 *           - RedisRateLimit=health,1000,16.6    ← Relaxed limits
 *
 * The factory's apply() method reads the YAML config and creates a custom GatewayFilter instance:
 *
 *     ┌─────────────────────┐         ┌───────────────────────────────┐         ┌──────────────────┐
 *     │  application.yml    │         │  This Factory Class           │         │  GatewayFilter   │
 *     │  ─────────────────  │ config  │  ───────────────────────────  │ creates │  ──────────────  │
 *     │  Name: payment      │────────▶│  apply(Config config) {       │────────▶│  Key: payment    │
 *     │  Capacity: 100      │         │    return filter(config);     │         │  Capacity: 100   │
 *     │  RefillRate: 1.66   │         │  }                            │         │  Rate: 1.66/sec  │
 *     └─────────────────────┘         └───────────────────────────────┘         └──────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🪣 TOKEN BUCKET ALGORITHM                                                                       │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * Imagine a bucket that:
 *   1. Holds max 100 tokens (capacity)
 *   2. Refills at 1.66 tokens/second (refillRate)
 *   3. Each request removes 1 token (tokensPerRequest)
 *   4. If bucket is empty → REJECT (429)
 *
 * Example Timeline:
 *   10:00:00 → User has 100 tokens
 *   10:00:01 → Makes 10 requests → 90 tokens left
 *   10:00:02 → 1.66 tokens added → ~92 tokens
 *   10:00:03 → Makes 95 requests → Denied! (Only 92 tokens available)
 *
 * Why Token Bucket vs Fixed Window?
 *   ❌ Fixed Window: Allows 100 req at 10:59:59 + 100 req at 11:00:00 = 200 req in 1 second!
 *   ✅ Token Bucket: Smooth rate, allows small bursts, no boundary spikes
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🔴 WHY REDIS? (Distributed System Problem)                                                      │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * Problem: We run MULTIPLE Gateway instances (Gateway-1, Gateway-2, Gateway-3)
 *
 * ❌ In-Memory Counter (WRONG):
 *     Gateway-1: User has 50 tokens
 *     Gateway-2: User has 50 tokens   ← Each gateway tracks separately!
 *     Gateway-3: User has 50 tokens
 *     Total: User can make 150 requests instead of 100!
 *
 * ✅ Redis (CORRECT):
 *     Redis: User has 50 tokens       ← Single source of truth
 *     Gateway-1 →┐
 *     Gateway-2 →├─→ Redis: 50 tokens
 *     Gateway-3 →┘
 *
 * Redis Key Format: "ratelimit:{service_name}:{client_id}"
 *   Example: "ratelimit:payment:user:12345"
 *            "ratelimit:trading:api:abc-123"
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ ⚡ RACE CONDITION PROBLEM & SOLUTION                                                             │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * Problem: Without atomic operations, concurrent requests corrupt the count
 *
 *     Time    Request-1           Request-2           Redis
 *     ────────────────────────────────────────────────────────
 *     T1      READ: 10 tokens     -                   10
 *     T2      -                   READ: 10 tokens     10
 *     T3      WRITE: 9 tokens     -                   9
 *     T4      -                   WRITE: 9 tokens     9  ← Should be 8!
 *
 * Solution: REDIS LUA SCRIPT (atomic execution)
 *   - Redis guarantees the ENTIRE script runs atomically
 *   - No other commands can interleave during script execution
 *   - Read-Check-Decrement happens as ONE indivisible operation
 *
 *     local current = redis.call('GET', key)
 *     if current >= cost then
 *         redis.call('DECRBY', key, cost)
 *         return {1, current - cost}  -- Allowed
 *     else
 *         return {0, current}         -- Denied
 *     end
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🎤 SYSTEM DESIGN INTERVIEW GUIDE - Building a Distributed Rate Limiter                         │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 *
 * STEP 1️⃣: Clarify Requirements (Ask Questions First!)
 *   Q: "Is this for a single server or distributed system?"
 *   A: Distributed gateway with multiple instances
 *
 *   Q: "What's the expected traffic volume?"
 *   A: High-volume trading API (thousands of req/sec)
 *
 *   Q: "How strict should enforcement be?"
 *   A: Must be accurate across all gateway instances (no cheating!)
 *
 * STEP 2️⃣: Choose Algorithm
 *   Option 1: Fixed Window (Simple but flawed)
 *     - Count requests per minute
 *     - Problem: 100 req at 10:59:59 + 100 req at 11:00:00 = burst!
 *
 *   Option 2: Token Bucket (Better) ✅
 *     - Allows controlled bursts (capacity)
 *     - Enforces sustained rate (refillRate)
 *     - Production-grade choice (used by AWS, GCP)
 *
 * STEP 3️⃣: Design Storage Layer
 *   Q: "Where do we store the token counts?"
 *
 *   ❌ Local Memory: Fast but not shared across instances
 *   ✅ Redis: Shared state, fast (sub-millisecond), supports atomic operations
 *
 *   Key Design: "ratelimit:{service}:{identifier}"
 *   Value Design: Hash with {tokens, lastRefill, ttl}
 *
 * STEP 4️⃣: Handle Concurrency (Critical!)
 *   Q: "What if 2 requests arrive simultaneously?"
 *
 *   Problem: Read-Modify-Write race condition
 *   Solution: Lua Script (atomic execution in Redis)
 *
 *   Mention: "Redis is single-threaded, so Lua scripts execute atomically"
 *
 * STEP 5️⃣: Configuration Design
 *   - capacity: Max tokens (burst size) → 100
 *   - refillRate: Tokens per second → 1.66 (for 100/min)
 *   - tokensPerRequest: Cost per call → 1 (could be 5 for expensive ops)
 *   - failOpen: What if Redis is down?
 *       * true: Allow traffic (degraded mode)
 *       * false: Block traffic (strict mode)
 *
 * STEP 6️⃣: Client Communication (Developer Experience)
 *   HTTP Headers (follow RFC 6585 standard):
 *     X-RateLimit-Limit: 100              ← Total capacity
 *     X-RateLimit-Remaining: 42           ← Tokens left
 *     X-RateLimit-Reset: 1678888888       ← Unix timestamp when bucket refills
 *     Retry-After: 30                     ← Seconds until retry (on 429)
 *
 *   Response on Limit Exceeded:
 *     HTTP 429 Too Many Requests
 *     {"error": "RATE_LIMIT_EXCEEDED", "retryAfter": 30}
 *
 * STEP 7️⃣: Optimizations & Production Concerns
 *   - Use Reactive/Async Redis (don't block gateway threads!)
 *   - Pipeline multiple Redis commands (reduce network latency)
 *   - Set TTL on keys (auto-cleanup, prevent memory leak)
 *   - Monitoring: Track 429 rates, Redis latency, fail-open events
 *   - Testing: Simulate Redis outages, concurrent load tests
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */
@Component
public class AlgoShareRateLimitFilterFactory extends
    AbstractGatewayFilterFactory<AlgoShareRateLimitFilterFactory.Config> {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RedisScript<List> rateLimiterScript;

    public AlgoShareRateLimitFilterFactory(
            ReactiveRedisTemplate<String, String> redisTemplate,
            RedisScript<List> rateLimiterScript) {
        super(Config.class);
        this.redisTemplate = redisTemplate;
        this.rateLimiterScript = rateLimiterScript;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String clientKey = getClientKey(exchange.getRequest(), config);

            return checkRateLimit(clientKey, config)
                .flatMap(result -> {
                    if (!result.isAllowed()) {
                        return handleRateLimitExceeded(
                            exchange, config, result.getRemaining(), result.getRetryAfter()
                        );
                    }
                    addRateLimitHeaders(exchange, config, result.getRemaining());
                    return chain.filter(exchange);
                })
                .onErrorResume(throwable -> 
                    handleRedisError(exchange, config, throwable, chain)
                );
        };
    }

    private Mono<RateLimitResult> checkRateLimit(String clientKey, Config config) {
        String redisKey = "ratelimit:" + config.getName() + ":" + clientKey;
        long now = Instant.now().getEpochSecond();

        List<String> keys = List.of(redisKey);
        List<String> args = Arrays.asList(
            String.valueOf(config.getCapacity()),
            String.valueOf(config.getRefillRate()),
            String.valueOf(config.getTokensPerRequest()),
            String.valueOf(now),
            String.valueOf(config.getTtl())
        );

        return redisTemplate.execute(rateLimiterScript, keys, args)
            .next()
            .map(result -> {
                List<Long> response = (List<Long>) result;
                return new RateLimitResult(
                    response.get(0) == 1,
                    response.get(1).intValue(),
                    response.get(2)
                );
            })
            .defaultIfEmpty(new RateLimitResult(true, config.getCapacity(), 0));
    }

    private String getClientKey(ServerHttpRequest request, Config config) {
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (userId != null && !userId.isEmpty()) {
            return "user:" + userId;
        }

        String apiKey = request.getHeaders().getFirst("X-API-Key");
        if (apiKey != null && !apiKey.isEmpty()) {
            return "api:" + apiKey;
        }

        return "ip:" + getClientIP(request);
    }

    private String getClientIP(ServerHttpRequest request) {
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIP = request.getHeaders().getFirst("X-Real-IP");
        if (xRealIP != null && !xRealIP.isEmpty()) {
            return xRealIP;
        }

        return request.getRemoteAddress() != null 
            ? request.getRemoteAddress().getAddress().getHostAddress() 
            : "unknown";
    }

    private void addRateLimitHeaders(ServerWebExchange exchange, Config config, int remaining) {
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().add("X-RateLimit-Limit", String.valueOf(config.getCapacity()));
        response.getHeaders().add("X-RateLimit-Remaining", String.valueOf(Math.max(0, remaining)));

        long resetTime = Instant.now().getEpochSecond() + config.getTtl();
        response.getHeaders().add("X-RateLimit-Reset", String.valueOf(resetTime));
    }

    private Mono<Void> handleRateLimitExceeded(
            ServerWebExchange exchange, Config config, int remaining, long retryAfter) {

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().add("Content-Type", "application/json");
        response.getHeaders().add("Retry-After", String.valueOf(retryAfter));
        response.getHeaders().add("X-RateLimit-Limit", String.valueOf(config.getCapacity()));
        response.getHeaders().add("X-RateLimit-Remaining", "0");

        String errorResponse = String.format(
            "{\"error\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests\",\"limit\":%d,\"retryAfter\":%d}",
            config.getCapacity(), retryAfter
        );

        DataBuffer buffer = response.bufferFactory().wrap(errorResponse.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> handleRedisError(
            ServerWebExchange exchange, Config config, Throwable throwable,
            org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {

        System.err.println("Redis error: " + throwable.getMessage());

        if (config.isFailOpen()) {
            return chain.filter(exchange);
        }

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        String error = "{\"error\":\"RATE_LIMITER_UNAVAILABLE\"}";
        DataBuffer buffer = response.bufferFactory().wrap(error.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList("name", "capacity", "refillRate");
    }

    public static class Config {
        private String name = "default";
        private int capacity = 100;
        private double refillRate = 1.66;
        private int tokensPerRequest = 1;
        private int ttl = 3600;
        private boolean failOpen = true;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public double getRefillRate() { return refillRate; }
        public void setRefillRate(double refillRate) { this.refillRate = refillRate; }
        public int getTokensPerRequest() { return tokensPerRequest; }
        public void setTokensPerRequest(int tokensPerRequest) { this.tokensPerRequest = tokensPerRequest; }
        public int getTtl() { return ttl; }
        public void setTtl(int ttl) { this.ttl = ttl; }
        public boolean isFailOpen() { return failOpen; }
        public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
    }

    private static class RateLimitResult {
        private final boolean allowed;
        private final int remaining;
        private final long retryAfter;

        public RateLimitResult(boolean allowed, int remaining, long retryAfter) {
            this.allowed = allowed;
            this.remaining = remaining;
            this.retryAfter = retryAfter;
        }

        public boolean isAllowed() { return allowed; }
        public int getRemaining() { return remaining; }
        public long getRetryAfter() { return retryAfter; }
    }
}
