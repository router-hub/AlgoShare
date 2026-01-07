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
 * Production-Grade Redis Rate Limiter for AlgoShare Gateway
 *
 * ------------------------------------------------------------------------------------------------------
 * Q: "Why is this called a 'Factory'?" (Factory Pattern)
 * ------------------------------------------------------------------------------------------------------
 * 1. Direct vs Factory:
 *    // Direct: Hard to configure per-route
 *    RateLimiter limiter = new RateLimiter(100, 10);
 *
 *    // Factory: Creates specific instances per route config
 *    GatewayFilter filter = factory.apply(config);
 *
 * 2. Visual Flow:
 *    ┌───────────────────────┐             ┌──────────────────────────────────┐
 *    │  application.yml      │             │  RedisRateLimitFilterFactory     │
 *    │  -------------------  │             │  ------------------------------  │
 *    │  filters:             │   MAPPED    │  @Override                       │
 *    │    - Name: payment    │ ──────────► │  apply(Config config) {          │
 *    │      Capacity: 100    │             │     return new Filter(config);   │
 *    │                       │             │  }                               │
 *    └───────────────────────┘             └───────────────┬──────────────────┘
 *                                                          │ Creates
 *                                                          ▼
 *                                          ┌──────────────────────────────────┐
 *                                          │  GatewayFilter Instance          │
 *                                          │  ----------------------          │
 *                                          │  Redis Key: "ratelimit:payment"  │
 *                                          │  Capacity: 100                   │
 *                                          └──────────────────────────────────┘
 *
 * ------------------------------------------------------------------------------------------------------
 * INTERVIEW STRATEGY: Distributed Rate Limiter Design (7-Step Guide)
 * ------------------------------------------------------------------------------------------------------
 * STEP 1: Clarify Requirements
 * - Scope: Distributed Gateway (Multiple Instances).
 * - Traffic: High Volume (Trading API).
 * - Consistency: Must be accurate across all instances (No local in-memory counters).
 *
 * STEP 2: Define Algorithm (Token Bucket)
 * - Why not Fixed Window? (Spikes at minute boundaries).
 * - Why Token Bucket? Allows bursts (capacity) but enforcing long-term rate (refill).
 *
 * STEP 3: Design Storage (Redis)
 * - Why Redis? Shared state for distributed instances.
 * - Data Structure: Hash or just Keys with TTL.
 * - Key Format: "ratelimit:{service_name}:{user_id}"
 *
 * STEP 4: Handle Concurrency (Race Conditions)
 * - Problem: Java Read-Modify-Write is NOT atomic.
 *   Thread A reads 10. Thread B reads 10. Both decrement to 9. Result: 9 (Should be 8).
 * - Solution: Redis Lua Script.
 *   Redis guarantees the script executes atomically. No other commands run in parallel.
 *
 * STEP 5: Design Configuration
 * - Capacity: Burst size (e.g., 100).
 * - RefillRate: Tokens/sec (e.g., 1.6 for 100/min).
 * - FailOpen: If Redis is down, do we block traffic (Critical) or let it pass (Non-critical)?
 *
 * STEP 6: Response Headers (UX)
 * - X-RateLimit-Limit: 100
 * - X-RateLimit-Remaining: 99
 * - X-RateLimit-Reset: 1678888888 (Unix Timestamp)
 * - 429 Too Many Requests: When remaining == 0.
 *
 * STEP 7: Optimization
 * - Pipeline: Reduce RTT (Round Trip Time).
 * - Async/Reactive: Use ReactiveRedisTemplate to avoid blocking Gateway threads.
 * ------------------------------------------------------------------------------------------------------
 */
@Component
public class RedisRateLimitFilterFactory extends 
    AbstractGatewayFilterFactory<RedisRateLimitFilterFactory.Config> {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RedisScript<List> rateLimiterScript;

    public RedisRateLimitFilterFactory(
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
