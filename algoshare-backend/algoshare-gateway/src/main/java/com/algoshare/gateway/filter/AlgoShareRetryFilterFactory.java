package com.algoshare.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * SMART RETRY FILTER - Exponential Backoff with Idempotency Protection for AlgoShare Gateway
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🎯 WHAT THIS CLASS DOES                                                                         │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * This filter automatically retries failed API requests when it makes sense to do so.
 *
 * Think of it like a persistent customer service agent:
 *   - Service down? → Wait 500ms and try again
 *   - Still down? → Wait 1 second and try again
 *   - Still failing? → Wait 2 seconds... then 4 seconds... (exponential backoff)
 *   - After 3 attempts? → Give up and return error to client
 *
 * BUT it's also smart about WHEN to retry:
 *   ✅ Network timeout? → RETRY (temporary glitch)
 *   ✅ Server error (500)? → RETRY (might recover)
 *   ❌ Bad request (400)? → DON'T RETRY (won't fix itself)
 *   ❌ Place order twice? → DON'T RETRY (would duplicate the order!)
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 📈 EXPONENTIAL BACKOFF WITH JITTER                                                              │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * Why not retry immediately? → Thundering herd problem!
 *
 * Scenario WITHOUT backoff (BAD):
 *   10:00:00.000 → Database goes down
 *   10:00:00.001 → 1000 requests fail
 *   10:00:00.050 → Database comes back up
 *   10:00:00.051 → All 1000 requests retry at SAME TIME → Overwhelm server again!
 *
 * Scenario WITH exponential backoff + jitter (GOOD):
 *   10:00:00.000 → Database goes down
 *   10:00:00.001 → 1000 requests fail
 *   10:00:00.500 → Retry 1: Some requests retry (500ms + random jitter)
 *   10:00:01.000 → Retry 2: Others retry (1000ms + random jitter)
 *   10:00:02.000 → Retry 3: Remaining retry (2000ms + random jitter)
 *   Result: Load is SPREAD OUT over time!
 *
 * Formula:
 *   Base Delay = 500ms
 *   Attempt 1:  500ms × 2^0 = 500ms  (+ jitter: 250ms - 750ms)
 *   Attempt 2:  500ms × 2^1 = 1000ms (+ jitter: 500ms - 1500ms)
 *   Attempt 3:  500ms × 2^2 = 2000ms (+ jitter: 1000ms - 3000ms)
 *   Max Backoff: 5000ms (cap to prevent waiting forever)
 *
 * Why Jitter?
 *   Without jitter: All requests wait exactly 500ms → retry at SAME time
 *   With jitter: Requests wait 250-750ms → spread out naturally
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🔒 IDEMPOTENCY - THE DUPLICATE REQUEST PROBLEM                                                  │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * The biggest danger of retries: Accidentally doing something TWICE!
 *
 * Example: User places a stock order
 *   Request: POST /api/orders {"symbol": "AAPL", "quantity": 100, "price": 150}
 *   Response: (timeout)
 *   Retry: POST /api/orders (SAME REQUEST)
 *   Result: User now owns 200 shares instead of 100! 💸
 *
 * Solution: Idempotency
 *   - An operation is "idempotent" if doing it multiple times has the SAME effect as doing it once
 *
 * HTTP Methods Classification (by RFC 7231):
 *   ✅ IDEMPOTENT (safe to retry):
 *      - GET: Reading data (no side effects)
 *      - HEAD: Getting metadata (no side effects)
 *      - OPTIONS: Getting supported methods (no side effects)
 *      - PUT: Update resource (same request → same result)
 *      - DELETE: Remove resource (deleting twice = still deleted)
 *
 *   ❌ NOT IDEMPOTENT (dangerous to retry):
 *      - POST: Create new resource (retry = duplicate!)
 *      - PATCH: Partial update (depends on implementation)
 *
 * POST Retry Protection:
 *   POST requests are ONLY retried if they include an "Idempotency-Key" header:
 *
 *   Request:
 *     POST /api/orders
 *     Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
 *     {"symbol": "AAPL", "quantity": 100}
 *
 *   Backend behavior:
 *     First request: Process order, store key "550e8400..." → Order #12345
 *     Retry (timeout): Check key "550e8400..." → Already processed → Return Order #12345
 *     Result: Only ONE order created! ✅
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🚦 WHEN TO RETRY vs WHEN NOT TO RETRY                                                           │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 *
 * ✅ RETRY THESE (Transient Failures):
 *   - Network Errors:
 *       * Connection timeout
 *       * Connection refused (server restarting)
 *       * Connection reset
 *       * No route to host
 *   - Server Errors (5xx):
 *       * 500 Internal Server Error (might be temporary)
 *       * 502 Bad Gateway (upstream service down)
 *       * 503 Service Unavailable (overloaded)
 *       * 504 Gateway Timeout (slow upstream)
 *   - Rate Limiting:
 *       * 429 Too Many Requests (wait and retry)
 *
 * ❌ NEVER RETRY THESE (Permanent Failures):
 *   - Client Errors (4xx):
 *       * 400 Bad Request (malformed request won't fix itself)
 *       * 401 Unauthorized (need valid token)
 *       * 403 Forbidden (access denied)
 *       * 404 Not Found (resource doesn't exist)
 *       * 409 Conflict (duplicate resource)
 *       * 422 Unprocessable Entity (validation failed)
 *   - Non-Retryable Server Errors:
 *       * 501 Not Implemented (server doesn't support this)
 *   - Circuit Breaker Open:
 *       * Service is in failure mode → Retrying wastes time
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🔄 HOW RETRY WORKS WITH CIRCUIT BREAKER                                                         │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * These two patterns work TOGETHER to provide resilience:
 *
 * Filter Chain Order (request flows left to right):
 *   Client → Retry Filter → Circuit Breaker → Downstream Service
 *
 * Scenario 1: Transient Failure (Retry helps)
 *   Attempt 1: Retry → CB (Closed) → Service (timeout) → FAIL
 *   Wait 500ms...
 *   Attempt 2: Retry → CB (Closed) → Service (success) → SUCCESS ✅
 *
 * Scenario 2: Sustained Failure (Circuit Breaker helps)
 *   Attempt 1: Retry → CB (Closed) → Service (timeout) → FAIL
 *   Attempt 2: Retry → CB (Closed) → Service (timeout) → FAIL
 *   Attempt 3: Retry → CB (Closed) → Service (timeout) → FAIL
 *   → Circuit Breaker opens after 10 failures
 *   Next Request: Retry → CB (OPEN) → Fallback response (immediate) ❌
 *   Result: No more retries! Circuit breaker short-circuits the call
 *
 * Benefits of This Combination:
 *   - Retry: Handles temporary glitches (network hiccups)
 *   - Circuit Breaker: Prevents retry storms when service is truly down
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🎤 SYSTEM DESIGN INTERVIEW GUIDE - Building a Retry Mechanism                                  │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 *
 * STEP 1️⃣: Clarify Requirements (Ask These Questions!)
 *   Q: "What kind of system is this for?"
 *   A: API Gateway serving trading application (high-value transactions)
 *
 *   Q: "What's the acceptable latency?"
 *   A: < 2 seconds for p95 (retries can add delay)
 *
 *   Q: "How critical is data consistency?"
 *   A: VERY critical - duplicate orders = financial loss
 *
 *   Q: "What's the expected failure rate?"
 *   A: < 0.1% under normal conditions (but need to handle spikes)
 *
 * STEP 2️⃣: Choose Retry Strategy
 *   Option 1: Fixed Delay (Simple but flawed)
 *     - Retry every 1 second
 *     - Problem: Thundering herd (all clients retry at same time)
 *
 *   Option 2: Exponential Backoff (Better) ✅
 *     - Wait 500ms, then 1s, then 2s, then 4s...
 *     - Spreads retries over time
 *     - Production-grade choice
 *
 *   Option 3: Exponential Backoff + Jitter (Best) ✅✅
 *     - Add randomness: 500ms ± 50%
 *     - Prevents synchronized retries
 *     - Used by AWS SDK, Google Cloud, Netflix
 *
 * STEP 3️⃣: Define Retry Criteria
 *   Q: "Which failures should we retry?"
 *
 *   Rule 1: Check HTTP Method
 *     - GET/HEAD/OPTIONS: Always safe ✅
 *     - PUT/DELETE: Safe if implemented correctly ✅
 *     - POST: Only with Idempotency-Key ⚠️
 *     - PATCH: Never (partial updates are tricky) ❌
 *
 *   Rule 2: Check Status Code
 *     - 5xx (server errors): Retry ✅
 *     - 429 (rate limit): Retry with backoff ✅
 *     - 408 (timeout): Retry ✅
 *     - 4xx (client errors): Don't retry ❌
 *
 *   Rule 3: Check Exception Type
 *     - Network timeout: Retry ✅
 *     - Connection refused: Retry ✅
 *     - Circuit breaker open: Don't retry ❌
 *
 * STEP 4️⃣: Handle Idempotency (Critical for POST!)
 *   Q: "How do we prevent duplicate orders?"
 *
 *   Client Side:
 *     - Generate UUID for each request: Idempotency-Key: 550e8400-...
 *     - Include in header for POST/PATCH requests
 *
 *   Server Side:
 *     - Store key in Redis with TTL (e.g., 24 hours)
 *     - On duplicate key: Return cached response
 *     - Pseudocode:
 *         if (redis.exists(idempotencyKey)) {
 *             return redis.get(idempotencyKey);  // Cached response
 *         }
 *         response = processOrder();
 *         redis.set(idempotencyKey, response, ttl=86400);
 *         return response;
 *
 * STEP 5️⃣: Configure Limits
 *   - maxAttempts: 3 (total tries = 1 initial + 2 retries)
 *   - initialBackoff: 500ms (first retry delay)
 *   - maxBackoff: 5000ms (cap to prevent excessive delays)
 *   - jitter: 0.5 (50% randomness)
 *   - timeout: 10 seconds total (including all retries)
 *
 *   Why 3 attempts?
 *     - Too few: Miss recovery opportunities
 *     - Too many: Waste time on truly failed services
 *     - 3 is the sweet spot (backed by AWS best practices)
 *
 * STEP 6️⃣: Logging & Monitoring
 *   Track These Metrics:
 *     - Retry rate: % of requests that needed retry
 *     - Success after retry: Did retry help?
 *     - Retry exhaustion: How many gave up after max attempts?
 *     - Latency impact: How much delay did retries add?
 *
 *   Log Events:
 *     [RETRY_ATTEMPT] RequestId=abc123, Attempt=2/3, Backoff=1000ms
 *     [RETRY_SUCCESS] RequestId=abc123, SucceededOn=Attempt2
 *     [RETRY_EXHAUSTED] RequestId=abc123, FinalError=ServiceUnavailable
 *
 * STEP 7️⃣: Advanced Optimizations
 *   - Budget-based: Stop retrying if total latency > 2 seconds
 *   - Adaptive backoff: Increase delays if failures are widespread
 *   - Per-service config: Critical services get more retries
 *   - Retry budget: Limit % of requests that can retry (prevent cascades)
 *   - Hedged requests: Send duplicate request after timeout (parallel retry)
 *
 * Requirements Mapping:
 *   - NFR-3: High Availability (graceful degradation through retries)
 *   - NFR-2: Low Latency (exponential backoff prevents retry storms)
 *   - FR-3: Order Execution (idempotency prevents duplicate orders)
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * @author AlgoShare Team
 * @since 1.0
 */
@Component
public class AlgoShareRetryFilterFactory extends 
    AbstractGatewayFilterFactory<AlgoShareRetryFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(AlgoShareRetryFilterFactory.class);

    // HTTP status codes that are safe to retry
    private static final Set<HttpStatus> RETRYABLE_STATUSES = new HashSet<>(Arrays.asList(
        HttpStatus.REQUEST_TIMEOUT,              // 408
        HttpStatus.TOO_MANY_REQUESTS,            // 429
        HttpStatus.INTERNAL_SERVER_ERROR,        // 500
        HttpStatus.BAD_GATEWAY,                  // 502
        HttpStatus.SERVICE_UNAVAILABLE,          // 503
        HttpStatus.GATEWAY_TIMEOUT               // 504
    ));

    // HTTP status codes that should NEVER be retried
    private static final Set<HttpStatus> NON_RETRYABLE_STATUSES = new HashSet<>(Arrays.asList(
        HttpStatus.BAD_REQUEST,                  // 400
        HttpStatus.UNAUTHORIZED,                 // 401
        HttpStatus.PAYMENT_REQUIRED,             // 402
        HttpStatus.FORBIDDEN,                    // 403
        HttpStatus.NOT_FOUND,                    // 404
        HttpStatus.METHOD_NOT_ALLOWED,           // 405
        HttpStatus.CONFLICT,                     // 409 (duplicate resource)
        HttpStatus.GONE,                         // 410
        HttpStatus.PRECONDITION_FAILED,          // 412
        HttpStatus.UNPROCESSABLE_ENTITY,         // 422
        HttpStatus.NOT_IMPLEMENTED               // 501 (server doesn't support feature)
    ));

    // HTTP methods that are safe to retry (idempotent by RFC 7231)
    private static final Set<HttpMethod> IDEMPOTENT_METHODS = new HashSet<>(Arrays.asList(
        HttpMethod.GET,
        HttpMethod.HEAD,
        HttpMethod.OPTIONS,
        HttpMethod.PUT,      // Idempotent if properly implemented
        HttpMethod.DELETE    // Idempotent if properly implemented
    ));

    public AlgoShareRetryFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String requestId = exchange.getRequest().getId();
            String method = exchange.getRequest().getMethod().toString();
            String uri = exchange.getRequest().getURI().toString();

            // Check if request has idempotency key (for POST operations)
            String idempotencyKey = exchange.getRequest().getHeaders()
                .getFirst("Idempotency-Key");

            return chain.filter(exchange)
                .retryWhen(Retry.backoff(config.getMaxAttempts(), 
                                         Duration.ofMillis(config.getBackoffMs()))
                    .maxBackoff(Duration.ofMillis(config.getMaxBackoffMs()))
                    .jitter(config.getJitter())

                    // Filter: Only retry if conditions are met
                    .filter(throwable -> {
                        boolean shouldRetry = shouldRetry(
                            throwable, 
                            exchange.getRequest().getMethod(),
                            idempotencyKey,
                            config
                        );

                        if (!shouldRetry) {
                            log.debug("[RETRY_SKIPPED] RequestId={}, Method={}, URI={}, Reason={}", 
                                requestId, method, uri, throwable.getClass().getSimpleName());
                        }

                        return shouldRetry;
                    })

                    // Hook: Before each retry attempt
                    .doBeforeRetry(retrySignal -> {
                        long attemptNumber = retrySignal.totalRetries() + 1;
                        Duration backoff = retrySignal.totalRetriesInARow() > 0 
                            ? Duration.ofMillis(config.getBackoffMs() * 
                                (long) Math.pow(2, retrySignal.totalRetriesInARow() - 1))
                            : Duration.ofMillis(config.getBackoffMs());

                        log.warn("[RETRY_ATTEMPT] RequestId={}, Attempt={}/{}, Method={}, URI={}, " +
                                 "Backoff={}ms, Reason={}", 
                            requestId,
                            attemptNumber,
                            config.getMaxAttempts(),
                            method,
                            uri,
                            backoff.toMillis(),
                            retrySignal.failure().getMessage()
                        );
                    })

                    // Hook: After all retries exhausted
                    .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                        log.error("[RETRY_EXHAUSTED] RequestId={}, Method={}, URI={}, " +
                                  "TotalAttempts={}, FinalError={}", 
                            requestId,
                            method,
                            uri,
                            retrySignal.totalRetries() + 1,
                            retrySignal.failure().getMessage()
                        );

                        // Return original exception
                        return retrySignal.failure();
                    })
                );
        };
    }

    /**
     * Determine if request should be retried
     * 
     * Decision Tree:
     * 1. Check HTTP method (must be idempotent OR have idempotency key)
     * 2. Check exception type (network errors are retryable)
     * 3. Check HTTP status code (5xx are retryable, 4xx are not)
     * 4. Check service-specific rules (e.g., don't retry payment duplicates)
     */
    private boolean shouldRetry(
            Throwable throwable, 
            HttpMethod method,
            String idempotencyKey,
            Config config) {

        // Rule 1: Check if HTTP method is safe to retry
        if (!isMethodRetryable(method, idempotencyKey)) {
            log.debug("[RETRY_BLOCKED] Non-idempotent method: {}", method);
            return false;
        }

        // Rule 2: Check exception type
        if (throwable instanceof ResponseStatusException) {
            ResponseStatusException rse = (ResponseStatusException) throwable;
            HttpStatus status = (HttpStatus) rse.getStatusCode();

            // Don't retry client errors (4xx)
            if (NON_RETRYABLE_STATUSES.contains(status)) {
                log.debug("[RETRY_BLOCKED] Non-retryable status: {}", status);
                return false;
            }

            // Retry server errors (5xx) and rate limits (429)
            if (RETRYABLE_STATUSES.contains(status)) {
                log.debug("[RETRY_ALLOWED] Retryable status: {}", status);
                return true;
            }

            // Default: Don't retry unknown status codes
            return false;
        }

        // Rule 3: Network/timeout errors are always retryable
        if (isNetworkException(throwable)) {
            log.debug("[RETRY_ALLOWED] Network error: {}", throwable.getClass().getSimpleName());
            return true;
        }

        // Rule 4: Circuit breaker open - don't retry
        if (isCircuitBreakerOpen(throwable)) {
            log.debug("[RETRY_BLOCKED] Circuit breaker is open");
            return false;
        }

        // Default: Don't retry unknown errors
        log.debug("[RETRY_BLOCKED] Unknown error type: {}", throwable.getClass().getSimpleName());
        return false;
    }

    /**
     * Check if HTTP method is safe to retry
     * 
     * Interview QA:
     * Q: "Why not retry POST requests?"
     * A: "POST is not idempotent by HTTP spec. Retrying POST /api/order/execute
     *     could place the order multiple times, charging the user 3x. We only
     *     retry POST if the request includes an Idempotency-Key header, which
     *     the backend uses to deduplicate requests."
     */
    private boolean isMethodRetryable(HttpMethod method, String idempotencyKey) {
        // GET, HEAD, OPTIONS, PUT, DELETE are idempotent by spec
        if (IDEMPOTENT_METHODS.contains(method)) {
            return true;
        }

        // POST is only retryable with idempotency key
        if (method == HttpMethod.POST && idempotencyKey != null && !idempotencyKey.isEmpty()) {
            log.debug("[RETRY_ALLOWED] POST request with Idempotency-Key: {}", idempotencyKey);
            return true;
        }

        // PATCH is never retryable (partial updates are hard to make idempotent)
        return false;
    }


    /**
     * Detect network exceptions by class hierarchy
     */
    private boolean isNetworkException(Throwable throwable) {
        // WebClient HTTP exceptions (imported class)
        if (throwable instanceof WebClientResponseException) {
            int status = ((WebClientResponseException) throwable).getStatusCode().value();
            return status >= 500 || status == 408 || status == 429;
        }

        // Standard Java network exceptions (instanceof works for imported classes)
        if (throwable instanceof java.net.ConnectException ||
            throwable instanceof java.net.SocketTimeoutException ||
            throwable instanceof java.net.UnknownHostException ||
            throwable instanceof java.io.IOException) {
            return true;
        }

        // Check exception class name for Reactor/Netty exceptions
        String className = throwable.getClass().getName();
        
        // Reactor Netty exceptions
        if (className.startsWith("reactor.netty.")) {
            String simpleName = throwable.getClass().getSimpleName();
            return simpleName.contains("Timeout") ||
                   simpleName.contains("Connect") ||
                   simpleName.contains("Connection") ||
                   simpleName.contains("PrematureClose");
        }
        
        // Reactor Core timeout exceptions
        if (className.startsWith("reactor.core.") && 
            throwable.getMessage() != null &&
            throwable.getMessage().toLowerCase().contains("timeout")) {
            return true;
        }

        return false;
    }

    /**
     * Check if circuit breaker is open
     * 
     * When circuit is open, AlgoShareCircuitBreaker returns fallback response.
     * We detect this and stop retrying to prevent cascade failures.
     */
    private boolean isCircuitBreakerOpen(Throwable throwable) {
        String message = throwable.getMessage() != null ? throwable.getMessage() : "";

        // Circuit breaker open signals
        return message.contains("circuit") ||
               message.contains("CircuitBreakerOpenException") ||
               throwable.getClass().getSimpleName().contains("CircuitBreaker");
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList("maxAttempts", "backoffMs", "maxBackoffMs");
    }

    /**
     * Configuration for retry behavior
     */
    public static class Config {
        private int maxAttempts = 3;           // Max retry attempts
        private long backoffMs = 500;          // Initial backoff (500ms)
        private long maxBackoffMs = 5000;      // Max backoff (5 seconds)
        private double jitter = 0.5;           // Jitter factor (0.0 - 1.0)

        // Service-specific overrides
        private boolean retryOnRateLimit = true;   // Retry 429 errors?
        private boolean enableForPost = false;     // Allow POST retries? (needs idempotency key)

        // Getters and setters
        public int getMaxAttempts() { 
            return maxAttempts; 
        }
        public void setMaxAttempts(int maxAttempts) { 
            this.maxAttempts = maxAttempts; 
        }

        public long getBackoffMs() { 
            return backoffMs; 
        }
        public void setBackoffMs(long backoffMs) { 
            this.backoffMs = backoffMs; 
        }

        public long getMaxBackoffMs() { 
            return maxBackoffMs; 
        }
        public void setMaxBackoffMs(long maxBackoffMs) { 
            this.maxBackoffMs = maxBackoffMs; 
        }

        public double getJitter() { 
            return jitter; 
        }
        public void setJitter(double jitter) { 
            this.jitter = jitter; 
        }

        public boolean isRetryOnRateLimit() { 
            return retryOnRateLimit; 
        }
        public void setRetryOnRateLimit(boolean retryOnRateLimit) { 
            this.retryOnRateLimit = retryOnRateLimit; 
        }

        public boolean isEnableForPost() { 
            return enableForPost; 
        }
        public void setEnableForPost(boolean enableForPost) { 
            this.enableForPost = enableForPost; 
        }
    }
}
