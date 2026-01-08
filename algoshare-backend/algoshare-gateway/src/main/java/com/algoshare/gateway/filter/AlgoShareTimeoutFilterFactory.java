package com.algoshare.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * TIMEOUT FILTER - Request Timeout Protection to Prevent Resource Exhaustion in AlgoShare Gateway
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🎯 WHAT THIS CLASS DOES                                                                         │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * This filter enforces a MAXIMUM TIME LIMIT for downstream service calls, preventing slow or
 * unresponsive services from holding gateway resources indefinitely.
 *
 * Think of it like a restaurant kitchen timer:
 *   - Order placed: Start timer (timeout = 5 seconds)
 *   - Service responds in 3s: Timer cancelled, request completes ✅
 *   - Service takes 6s: Timer fires → Cancel request, return 504 Gateway Timeout ❌
 *   - Service never responds: Timer fires → Free up resources, don't wait forever
 *
 * Example Flow:
 *   Request A → Payment Service (responds in 200ms) → SUCCESS ✅
 *   Request B → Report Service (responds in 3s) → SUCCESS ✅
 *   Request C → Analytics Service (hangs for 10s) → TIMEOUT after 5s ❌
 *   Request D → Dead Service (never responds) → TIMEOUT after 5s ❌
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ ⏱️ WHY ARE TIMEOUTS CRITICAL?                                                                   │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * Without timeouts, slow/dead services can EXHAUST gateway resources:
 *
 * ❌ WITHOUT Timeout (Cascade Failure):
 *   - Analytics Service becomes unresponsive (database locked)
 *   - 1000 requests pile up, each waiting indefinitely
 *   - Gateway thread pool exhausted (all threads blocked)
 *   - Health checks timeout → Load balancer marks gateway DEAD
 *   - Entire gateway DOWN due to ONE bad service! 💥
 *
 * ✅ WITH Timeout (Contained Failure):
 *   - Analytics Service becomes unresponsive
 *   - Each request times out after 5 seconds
 *   - Threads released after 5s → Available for other services
 *   - Circuit breaker opens after 50% failures
 *   - Analytics fails, but Trading/Payment/Profile still work! ✅
 *
 * Real-World Scenario (Netflix API Gateway):
 *   - Video recommendation service had DB deadlock
 *   - Without timeout: All gateway threads stuck waiting
 *   - Result: Entire Netflix site DOWN (not just recommendations)
 *   - Fix: Add 2-second timeout → Recommendation fails but UI loads
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🧵 REACTIVE TIMEOUTS vs BLOCKING TIMEOUTS                                                       │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * Traditional blocking model vs Reactive model:
 *
 * ❌ BLOCKING (Thread-Per-Request):
 *   HTTP Request → Assign Thread → Wait for response → Release Thread
 *
 *   Code:
 *     Thread thread = ThreadPool.assignThread();     // 1MB stack memory
 *     Response response = service.call();            // Thread BLOCKED here
 *     return response;
 *
 *   Resource Cost:
 *     - 1000 concurrent requests = 1000 threads = 1GB memory
 *     - Thread context switching overhead
 *     - Limited scalability (max ~10K threads)
 *
 * ✅ REACTIVE (Event Loop):
 *   HTTP Request → Register callback → Process other requests → Callback fires
 *
 *   Code:
 *     return service.call()                          // No thread blocking!
 *         .timeout(Duration.ofSeconds(5))            // Timeout on event loop
 *         .onErrorResume(ex -> handleTimeout(ex));   // Non-blocking error handler
 *
 *   Resource Cost:
 *     - 1000 concurrent requests = ~10 event loop threads = 10MB memory
 *     - No context switching
 *     - High scalability (100K+ requests)
 *
 * How Reactive Timeout Works:
 *   1. Request initiated → Schedule timeout task on Reactor scheduler
 *   2. If response arrives before timeout → Cancel timeout task ✅
 *   3. If timeout fires first → Cancel downstream call, emit TimeoutException ❌
 *   4. Error handler catches exception → Return 504 to client
 *
 * Visual Timeline:
 *   T=0s     Request sent to downstream service
 *            ↓
 *   T=0s     Reactor schedules timeout task (fire at T=5s)
 *            ↓
 *   T=3s     Downstream responds → Cancel timeout task → Return response ✅
 *
 *   Alternative Timeline (Timeout):
 *   T=0s     Request sent to downstream service
 *            ↓
 *   T=0s     Reactor schedules timeout task (fire at T=5s)
 *            ↓
 *   T=5s     Timeout fires → Cancel downstream call → Emit TimeoutException ❌
 *            ↓
 *   T=5s     onErrorResume() catches exception → Return 504 Gateway Timeout
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🔄 TIMEOUT INTEGRATION WITH CIRCUIT BREAKER                                                     │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * Timeouts and circuit breakers work together for defense in depth:
 *
 * Filter Chain Order:
 *   Client → Retry → Timeout → Circuit Breaker → Bulkhead → Downstream Service
 *
 * Scenario 1: Slow Service (Timeout opens Circuit Breaker)
 *   Request 1: Payment Service takes 6s → Timeout fires (5s) → Failure ❌
 *   Request 2: Payment Service takes 7s → Timeout fires (5s) → Failure ❌
 *   ...
 *   Request 50: 50% error rate reached → Circuit Breaker OPENS
 *   Request 51-100: Circuit Breaker rejects instantly (no timeout needed)
 *
 *   Benefit: After circuit opens, no more 5-second waits!
 *            Fast failure (1ms rejection) instead of 5s timeout.
 *
 * Scenario 2: Intermittent Slow Requests
 *   Request 1: 200ms → SUCCESS ✅
 *   Request 2: 6s → TIMEOUT ❌
 *   Request 3: 300ms → SUCCESS ✅
 *   Request 4: 7s → TIMEOUT ❌
 *
 *   Circuit Breaker State: 50% failure rate but doesn't open (threshold = 60%)
 *   Result: Timeouts protect individual requests, circuit breaker waits for consistent failures
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 💾 MEMORY LEAK PREVENTION - Resource Cleanup                                                   │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * Timeouts must CANCEL downstream calls to prevent leaks:
 *
 * ❌ BAD (Naive Timeout):
 *   Mono<Response> response = service.call();
 *   return response.timeout(Duration.ofSeconds(5));
 *
 *   Problem: Downstream call continues even after timeout!
 *     T=0s     Request sent to service
 *     T=5s     Timeout fires → Return 504 to client
 *     T=10s    Service finally responds → But nobody is listening!
 *     Result: Wasted resources (connection held for 10s, not 5s)
 *
 * ✅ GOOD (Proper Cancellation):
 *   Mono<Response> response = service.call();
 *   return response
 *       .timeout(Duration.ofSeconds(5))
 *       .doOnCancel(() -> {
 *           log.debug("Cancelling downstream call due to timeout");
 *           // Reactor automatically propagates cancellation signal
 *       });
 *
 *   How Reactor Handles Cancellation:
 *     T=0s     Subscribe to downstream service
 *     T=5s     Timeout fires → Reactor sends CANCEL signal upstream
 *     T=5s     HTTP client receives cancel → Closes connection
 *     T=5s     Return 504 to client
 *     Result: Connection closed immediately at 5s ✅
 *
 * HTTP Connection Pool Impact:
 *   Without proper cancellation:
 *     - 100 requests timeout at 5s
 *     - Connections stay open for 30s (TCP timeout)
 *     - Connection pool exhausted: 100/100 connections stuck
 *     - New requests fail: "Connection pool exhausted"
 *
 *   With proper cancellation:
 *     - 100 requests timeout at 5s
 *     - Connections closed at 5s
 *     - Connection pool freed: 0/100 connections in use
 *     - New requests succeed ✅
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 📊 TIMEOUT SELECTION GUIDE                                                                      │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * How to choose appropriate timeout values:
 *
 * Method 1: Percentile-Based (Recommended)
 *   1. Measure service latency under normal load
 *   2. Calculate P99 latency (99th percentile)
 *   3. Set timeout = P99 × 1.5 (50% buffer)
 *
 *   Example:
 *     Payment Service latency:
 *       P50 = 100ms
 *       P95 = 300ms
 *       P99 = 500ms
 *       Timeout = 500ms × 1.5 = 750ms → Round to 1000ms
 *
 * Method 2: SLA-Based
 *   If service has SLA (e.g., "95% of requests under 2 seconds"):
 *     Timeout = SLA limit + buffer
 *     Timeout = 2000ms + 500ms = 2500ms
 *
 * Service Category Recommendations:
 *   - Fast Services (Cache, DB lookup): 500ms - 1s
 *   - Medium Services (REST API, Queries): 2s - 5s
 *   - Slow Services (Reports, Batch Jobs): 10s - 30s
 *   - External APIs (Third-party): 5s - 10s (can't control their latency)
 *
 * Timeout Hierarchy (Layered Defense):
 *   Client Timeout (10s)
 *     ↓
 *   Gateway Timeout (5s)  ← This filter
 *     ↓
 *   Service Timeout (3s)
 *     ↓
 *   Database Timeout (1s)
 *
 *   Rule: Each layer should have SHORTER timeout than the layer above
 *   Reason: Inner layer fails fast → Outer layer has time to retry/fallback
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🎤 SYSTEM DESIGN INTERVIEW GUIDE - Building a Timeout Pattern                                  │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 *
 * STEP 1️⃣: Clarify Requirements (Ask Questions!)
 *   Q: "What problem are we solving?"
 *   A: Prevent slow/unresponsive services from holding gateway resources
 *
 *   Q: "What's the expected response time?"
 *   A: 95% of requests should complete within 2 seconds
 *
 *   Q: "Is this a synchronous or asynchronous system?"
 *   A: Reactive (Spring WebFlux, async) → Use reactive timeouts
 *
 *   Q: "What happens when a timeout occurs?"
 *   A: Return 504 Gateway Timeout, log error, increment metrics
 *
 * STEP 2️⃣: Choose Timeout Strategy
 *   Option 1: Global Timeout (Simple)
 *     - Same timeout for all services (e.g., 5 seconds)
 *     - Pros: Easy to configure, uniform behavior
 *     - Cons: Doesn't account for fast vs slow services
 *
 *   Option 2: Per-Service Timeout (Flexible) ✅
 *     - Different timeout per service:
 *         Payment: 1s (fast DB lookup)
 *         Report: 30s (complex aggregation)
 *         Trading: 500ms (real-time data)
 *     - Pros: Optimized for each service's characteristics
 *     - Cons: More configuration, needs monitoring
 *
 *   Option 3: Dynamic Timeout (Advanced)
 *     - Timeout adjusts based on current load/latency
 *     - If P99 latency increases → Increase timeout
 *     - If load decreases → Decrease timeout
 *     - Pros: Self-adaptive
 *     - Cons: Complex, can be unpredictable
 *
 * STEP 3️⃣: Implement Reactive Timeout (Spring WebFlux)
 *   Key Challenges:
 *     - Don't block threads (use Reactor operators)
 *     - Properly cancel downstream calls on timeout
 *     - Handle timeout exceptions gracefully
 *
 *   Code Structure:
 *     return chain.filter(exchange)
 *         .timeout(Duration.ofMillis(config.getTimeoutMs()))
 *         .onErrorResume(throwable -> {
 *             if (isTimeoutException(throwable)) {
 *                 return handleTimeout(exchange, config);
 *             }
 *             return Mono.error(throwable);  // Propagate non-timeout errors
 *         });
 *
 *   Why onErrorResume instead of doOnError:
 *     - doOnError: Side effect only, doesn't modify response
 *     - onErrorResume: Catches exception, returns custom response ✅
 *
 * STEP 4️⃣: Ensure Proper Resource Cleanup
 *   Q: "What happens to the downstream call when timeout fires?"
 *   A: Must be CANCELLED to free resources
 *
 *   How Reactor Handles This:
 *     - .timeout() operator sends CANCEL signal upstream
 *     - HTTP client (WebClient, RestTemplate) receives signal
 *     - Connection closed, resources freed
 *
 *   Validation:
 *     - Monitor connection pool metrics
 *     - If pool exhausts → Cancellation not working
 *     - If pool stays healthy → Proper cleanup ✅
 *
 * STEP 5️⃣: Timeout Exception Handling
 *   Challenge: Different libraries throw different timeout exceptions
 *     - java.util.concurrent.TimeoutException
 *     - reactor.core.publisher.TimeoutException
 *     - io.netty.handler.timeout.ReadTimeoutException
 *
 *   Solution: Generic timeout detection
 *     boolean isTimeoutException(Throwable t) {
 *         String message = t.getMessage().toLowerCase();
 *         return message.contains("timeout") ||
 *                t instanceof TimeoutException ||
 *                (t.getCause() != null && isTimeoutException(t.getCause()));
 *     }
 *
 * STEP 6️⃣: Monitoring & Observability
 *   Metrics to Track:
 *     - timeout.total: Total timeout errors
 *     - timeout.rate: Timeouts per second
 *     - timeout.percentage: Timeout % (should be < 1%)
 *     - service.latency.p99: Track if latency approaching timeout
 *
 *   Alerts:
 *     - Timeout rate > 5% → Service degradation or timeout too aggressive
 *     - P99 latency > 80% of timeout → Timeout likely to fire soon
 *     - Sudden spike in timeouts → Service outage or network issue
 *
 *   Headers (Client Visibility):
 *     X-Timeout-Service: payment-service
 *     X-Timeout-Duration: 5000
 *     X-Timeout-Timestamp: 2026-01-08T17:38:23Z
 *
 * STEP 7️⃣: Production Considerations
 *   - Retry Strategy: Should retries have SAME timeout or SHORTER?
 *       Recommendation: Exponentially decreasing timeouts
 *       Retry 1: 5s
 *       Retry 2: 3s (don't wait as long for already-slow service)
 *       Retry 3: 1s (fast failure)
 *
 *   - Circuit Breaker Integration: Timeout failures count toward circuit breaker
 *       50 timeouts in 100 requests → Circuit breaker opens
 *
 *   - Graceful Degradation: Return cached data instead of error
 *       .timeout(5s)
 *       .onErrorResume(TimeoutException ->
 *           cache.get(key).defaultIfEmpty(fallbackValue)
 *       )
 *
 *   - Testing:
 *       - Unit test: Mock slow service, verify timeout fires
 *       - Integration test: Wiremock with delayed response
 *       - Load test: Ensure timeouts don't cause cascade failures
 *
 * Common Interview Follow-ups:
 *   Q: "What if client has 10s timeout but gateway has 5s timeout?"
 *   A: Gateway times out at 5s, client waits 5 more seconds (wasted)
 *      Rule: Client timeout > Gateway timeout (10s > 5s) ✅
 *
 *   Q: "How does timeout differ from circuit breaker?"
 *   A: Timeout: Single request protection (per-request)
 *      Circuit Breaker: Aggregate failure prevention (tracks failure rate)
 *
 *   Q: "Can timeouts cause more load on downstream service?"
 *   A: Yes! If timeout triggers retry, and retry also times out
 *      Solution: Exponential backoff, circuit breaker
 *
 *   Q: "What if downstream service has variable latency (sometimes 1s, sometimes 30s)?"
 *   A: Set timeout to P99 latency (e.g., 10s)
 *      Trade-off: 1% of requests might timeout, but 99% don't wait 30s
 *
 * Requirements Mapping:
 *   - NFR-3: High Availability (prevent cascade failures from slow services)
 *   - NFR-2: Low Latency (fail fast instead of waiting indefinitely)
 *   - NFR-1: Scalability (release resources quickly, don't exhaust thread pool)
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */
@Component
public class AlgoShareTimeoutFilterFactory extends AbstractGatewayFilterFactory<AlgoShareTimeoutFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(AlgoShareTimeoutFilterFactory.class);

    public AlgoShareTimeoutFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String requestId = exchange.getRequest().getId();

            return chain.filter(exchange)
                    .timeout(Duration.ofMillis(config.getTimeoutMs()))
                    .doOnSuccess(response ->
                            log.debug("[TIMEOUT_SUCCESS] RequestId={} Duration={}ms", requestId, config.getTimeoutMs()))
                    .doOnCancel(() ->
                            log.warn("[CANCEL_SENT] RequestId={} Service={}", requestId, config.getServiceName()))
                    .onErrorResume(throwable -> {
                        if (isTimeoutException(throwable)) {
                            log.warn("[TIMEOUT] RequestId={} Service={} Timeout={}ms",
                                    requestId, config.getServiceName(), config.getTimeoutMs());
                            return handleTimeout(exchange, config);
                        }
                        return Mono.error(throwable);
                    });
        };
    }

    private boolean isTimeoutException(Throwable throwable) {
        if (throwable == null) return false;

        String message = (throwable.getMessage() + " " +
                (throwable.getCause() != null ? throwable.getCause().getMessage() : ""))
                .toLowerCase();

        return message.contains("timeout");
    }


    private Mono<Void> handleTimeout(ServerWebExchange exchange, Config config) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.GATEWAY_TIMEOUT);
        response.getHeaders().add("Content-Type", "application/json");
        response.getHeaders().add("X-Timeout-Service", config.getServiceName());
        response.getHeaders().add("X-Timeout-Duration", String.valueOf(config.getTimeoutMs()));

        String errorResponse = String.format("""
            {
                "error": "TIMEOUT",
                "message": "Request timeout after %d ms",
                "service": "%s",
                "timeout": %d,
                "timestamp": "%s"
            }
            """, config.getTimeoutMs(), config.getServiceName(), config.getTimeoutMs(),
                java.time.Instant.now().toString());

        DataBuffer buffer = response.bufferFactory()
                .wrap(errorResponse.getBytes(StandardCharsets.UTF_8));

        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList("serviceName", "timeoutMs");
    }

    public static class Config {
        private String serviceName = "unknown";
        private long timeoutMs = 5000;

        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
