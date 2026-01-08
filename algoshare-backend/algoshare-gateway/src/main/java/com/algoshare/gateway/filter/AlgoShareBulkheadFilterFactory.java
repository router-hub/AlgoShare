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
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * BULKHEAD FILTER - Concurrency Limiter to Prevent Resource Exhaustion in AlgoShare Gateway
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🎯 WHAT THIS CLASS DOES                                                                         │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * This filter protects downstream services from being overwhelmed by limiting the number of
 * CONCURRENT requests that can be processed at the same time.
 *
 * Think of it like a restaurant with limited tables:
 *   - Restaurant has 50 tables (maxConcurrent = 50)
 *   - Customer arrives: If empty table exists → Seat them ✅
 *   - Customer arrives: If all tables full → Put in waiting area (queue) ⏳
 *   - Waiting area full (10 people max) → Turn away (429 error) ❌
 *   - Customer finishes meal → Table becomes available for next customer
 *
 * Example Flow:
 *   Request 1-50: All admitted (50 concurrent)
 *   Request 51-60: Added to queue (waiting)
 *   Request 61+: REJECTED with 429 Too Many Requests
 *   Request 1 completes → Request 51 gets admitted from queue
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🚢 WHY IS IT CALLED "BULKHEAD"?                                                                 │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * The name comes from ship design. Ships have watertight compartments (bulkheads) that prevent
 * water from flooding the entire vessel if one section is breached.
 *
 * In software, bulkheads ISOLATE failures:
 *
 * ❌ WITHOUT Bulkhead (One slow service kills everything):
 *   - Payment Service is slow (takes 30 seconds per request)
 *   - All 1000 gateway threads get stuck waiting for Payment Service
 *   - Health Check endpoint can't respond (all threads busy)
 *   - Load balancer marks gateway as DEAD
 *   - Cascade failure! 💥
 *
 * ✅ WITH Bulkhead (Failures are contained):
 *   - Payment Service gets 50 concurrent threads (bulkhead limit)
 *   - Payment Service is slow → Only 50 threads blocked
 *   - Remaining 950 threads serve other services (Health, Trading, Profile)
 *   - Gateway stays healthy! ✅
 *
 * Visual Diagram:
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │  Gateway Thread Pool (1000 threads total)                    │
 *   │  ──────────────────────────────────────────────────────────  │
 *   │  ┌─────────────┐  ┌─────────────┐  ┌──────────────────────┐ │
 *   │  │   Payment   │  │   Trading   │  │   Health/Profile     │ │
 *   │  │  Bulkhead   │  │  Bulkhead   │  │   (Unlimited)        │ │
 *   │  │  Max: 50    │  │  Max: 100   │  │                      │ │
 *   │  │             │  │             │  │                      │ │
 *   │  │  [SLOW] ⏳  │  │  [FAST] ✅  │  │  [FAST] ✅           │ │
 *   │  └─────────────┘  └─────────────┘  └──────────────────────┘ │
 *   └──────────────────────────────────────────────────────────────┘
 *
 *   Result: Even if Payment is slow, Trading and Health stay responsive!
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ ⚛️ ATOMICITY - THE RACE CONDITION PROBLEM                                                       │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * Problem: Without atomic operations, concurrent requests can bypass the limit!
 *
 * Scenario (maxConcurrent = 50, using non-atomic counter):
 *   Time    Thread-A              Thread-B              Counter
 *   ────────────────────────────────────────────────────────────
 *   T1      READ: counter = 49    -                     49
 *   T2      -                     READ: counter = 49    49
 *   T3      Check: 49 < 50 ✅     -                     49
 *   T4      -                     Check: 49 < 50 ✅     49
 *   T5      WRITE: counter = 50   -                     50
 *   T6      ADMIT Thread-A ✅     -                     50
 *   T7      -                     WRITE: counter = 51  51 ← BUG!
 *   T8      -                     ADMIT Thread-B ✅     51
 *
 *   Result: 51 concurrent requests (should be max 50)!
 *
 * Solution: AtomicInteger with Compare-And-Set (CAS)
 *   - Read current value
 *   - Calculate new value (current + 1)
 *   - Atomically update ONLY if current value hasn't changed
 *   - If it changed → Retry from step 1
 *
 * Code:
 *   boolean tryAdmit() {
 *       int current = currentConcurrent.get();           // Read
 *       if (current >= maxConcurrent) return false;      // Check
 *       return currentConcurrent.compareAndSet(          // Atomic update
 *           current,      // Expected value
 *           current + 1   // New value
 *       );
 *   }
 *
 * How CAS Works:
 *   Thread-A: CAS(49 → 50)  → Success (counter was 49) ✅
 *   Thread-B: CAS(49 → 50)  → FAIL (counter is now 50, not 49) ❌
 *   Thread-B: Retry → Read 50 → 50 >= 50 → REJECT ✅
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 📊 QUEUEING vs IMMEDIATE REJECTION                                                              │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * When bulkhead is full, you have two options:
 *
 * Option 1: IMMEDIATE REJECTION (queueCapacity = 0)
 *   - Request arrives → Bulkhead full → Instant 429
 *   - Pros: Fast feedback, client can retry other instances
 *   - Cons: Higher client-side complexity
 *   - Use case: Microservices with client-side load balancing
 *
 * Option 2: QUEUEING (queueCapacity = 10)
 *   - Request arrives → Bulkhead full → Wait in queue (up to 2 seconds)
 *   - If slot opens → Process request
 *   - If timeout → 429 error
 *   - Pros: Higher success rate, smoother traffic
 *   - Cons: Added latency, memory overhead
 *   - Use case: User-facing APIs, single gateway instance
 *
 * Configuration Example:
 *   Critical Services (strict):
 *     maxConcurrent: 50
 *     queueCapacity: 0          ← Fail fast
 *     queueTimeout: 0
 *
 *   Best-effort Services (lenient):
 *     maxConcurrent: 100
 *     queueCapacity: 20         ← Allow waiting
 *     queueTimeout: 3000ms
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🔄 HOW BULKHEAD WORKS WITH OTHER RESILIENCE PATTERNS                                            │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * Bulkhead integrates with Circuit Breaker, Retry, and Timeout:
 *
 * Filter Chain Order:
 *   Client → Retry → Bulkhead → Circuit Breaker → Timeout → Downstream Service
 *
 * Scenario 1: Fast Failure (Circuit Breaker Open)
 *   Request → Retry → Bulkhead (admitted) → Circuit Breaker (OPEN) → Fallback
 *   Time: 1ms (instant rejection, bulkhead slot released immediately)
 *
 * Scenario 2: Slow Service (Timeout)
 *   Request → Retry → Bulkhead (admitted) → CB (Closed) → Timeout (after 5s)
 *   Time: 5 seconds (bulkhead slot held for 5 seconds!)
 *
 * Problem: Slow services HOG bulkhead slots!
 *   - 50 concurrent limit
 *   - Each request waits 5 seconds
 *   - Throughput: 50/5 = 10 requests/second (BAD!)
 *
 * Solution: TIMEOUT must be BEFORE bulkhead
 *   Client → Retry → Timeout (2s) → Bulkhead → Circuit Breaker → Service
 *   Now: Bulkhead slots released after 2s max
 *   Throughput: 50/2 = 25 requests/second (BETTER!)
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🧹 MEMORY LEAK PREVENTION                                                                       │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 * Problem: Bulkheads are created dynamically per service name
 *   - bulkheads.put("payment", new BulkheadState())
 *   - bulkheads.put("trading", new BulkheadState())
 *   - bulkheads.put("profile", new BulkheadState())
 *
 * What if a service is deleted or renamed?
 *   - Old bulkhead state stays in memory FOREVER
 *   - After 1 year: 1000s of dead bulkheads = Memory leak!
 *
 * Solution: Background Cleanup Task
 *   - Every 5 minutes: Scan all bulkheads
 *   - If lastAccess > 1 hour ago → Remove
 *   - Logs: [BULKHEAD_CLEANUP] Removed 3 idle bulkheads
 *
 * Code Flow:
 *   startCleanupTask() {
 *       Every 5 minutes:
 *           cutoff = now - 1 hour
 *           for each bulkhead:
 *               if (bulkhead.lastAccess < cutoff):
 *                   remove(bulkhead)
 *   }
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ 🎤 SYSTEM DESIGN INTERVIEW GUIDE - Building a Bulkhead Pattern                                 │
 * └─────────────────────────────────────────────────────────────────────────────────────────────────┘
 *
 * STEP 1️⃣: Clarify Requirements (Ask Questions!)
 *   Q: "What problem are we solving?"
 *   A: Prevent slow services from consuming all gateway threads
 *
 *   Q: "How many downstream services?"
 *   A: 10-20 microservices with varying performance characteristics
 *
 *   Q: "What's the acceptable error rate?"
 *   A: < 1% under normal load, fail fast when overloaded
 *
 *   Q: "Do we need per-user limits or per-service limits?"
 *   A: Per-service (user limits are handled by rate limiter)
 *
 * STEP 2️⃣: Choose Bulkhead Strategy
 *   Option 1: Thread Pool Bulkhead (Traditional)
 *     - Dedicated thread pool per service
 *     - Pros: True isolation (CPU, memory)
 *     - Cons: High memory overhead (threads are expensive)
 *
 *   Option 2: Semaphore Bulkhead (Modern) ✅
 *     - Counter-based concurrency limit
 *     - Pros: Lightweight, reactive-friendly
 *     - Cons: No CPU isolation (all share same threads)
 *     - Best for: Reactive systems (Spring WebFlux, Reactor)
 *
 * STEP 3️⃣: Define Concurrency Limits
 *   Q: "How do we determine maxConcurrent?"
 *
 *   Calculation:
 *     Target Throughput = 100 requests/second
 *     Avg Response Time = 500ms = 0.5 seconds
 *     Little's Law: Concurrency = Throughput × Latency
 *                                = 100 × 0.5 = 50 concurrent
 *
 *   Add buffer (20-30%):
 *     maxConcurrent = 50 × 1.25 = 62 → Round to 60
 *
 *   Service Classification:
 *     - Critical (Payment): 50 (strict limit)
 *     - High-volume (Trading): 100 (more capacity)
 *     - Best-effort (Reports): 20 (can fail)
 *
 * STEP 4️⃣: Handle Concurrency Safely (Atomic Operations)
 *   Q: "Why not use simple int counter?"
 *   A: Race conditions! Multiple threads can bypass limit.
 *
 *   Solutions:
 *     ❌ synchronized (simple but slow, blocks threads)
 *     ❌ Lock (complex, can deadlock)
 *     ✅ AtomicInteger with CAS (lock-free, fast, correct)
 *
 *   Key Methods:
 *     - compareAndSet(expected, new): Atomic increment
 *     - updateAndGet(lambda): Atomic decrement
 *
 * STEP 5️⃣: Decide on Queueing Behavior
 *   No Queue (queueCapacity = 0):
 *     - Use when: Multiple gateway instances (client can retry elsewhere)
 *     - Benefit: Fast failure, clear metrics
 *
 *   With Queue (queueCapacity > 0):
 *     - Use when: Single gateway, user-facing API
 *     - Benefit: Higher success rate
 *     - Tradeoff: Added latency (2-3 seconds)
 *
 *   Queue Sizing:
 *     Too small: Doesn't help
 *     Too large: High latency, memory overhead
 *     Sweet spot: 10-20% of maxConcurrent (e.g., 50 → queue 10)
 *
 * STEP 6️⃣: Monitoring & Observability
 *   Metrics to Track:
 *     - bulkhead.concurrent.current: Current concurrent requests
 *     - bulkhead.concurrent.max: High water mark
 *     - bulkhead.admitted.total: Total requests admitted
 *     - bulkhead.rejected.total: Total requests rejected
 *     - bulkhead.queue.waiting: Requests in queue
 *
 *   Alerts:
 *     - Concurrent > 80% of max → Service under load
 *     - Rejection rate > 5% → Increase capacity or scale
 *     - Queue always full → Consider removing queue
 *
 *   Headers (Client Visibility):
 *     X-Bulkhead-Name: payment-service
 *     X-Bulkhead-Reason: CAPACITY_EXCEEDED
 *     X-Bulkhead-Concurrent: 50/50
 *
 * STEP 7️⃣: Production Considerations
 *   - Dynamic Configuration: Allow runtime changes (no restart)
 *   - Memory Leak Prevention: Cleanup idle bulkheads (TTL)
 *   - Graceful Shutdown: Drain in-flight requests
 *   - Testing: Load test to validate limits
 *   - Documentation: Document per-service limits in code
 *
 * Common Interview Follow-ups:
 *   Q: "What if downstream has its own rate limit?"
 *   A: Our bulkhead should be <= downstream limit to prevent errors
 *
 *   Q: "How does this differ from rate limiting?"
 *   A: Rate limiter: Requests per TIME (100/minute)
 *      Bulkhead: CONCURRENT requests (50 at same time)
 *
 *   Q: "What if one user sends 1000 concurrent requests?"
 *   A: They could consume entire bulkhead! Add per-user rate limiter.
 *
 * Requirements Mapping:
 *   - NFR-3: High Availability (isolate failures, prevent cascades)
 *   - NFR-2: Low Latency (protect fast services from slow ones)
 *   - NFR-1: Scalability (prevent resource exhaustion)
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 */
@Component
public class AlgoShareBulkheadFilterFactory extends AbstractGatewayFilterFactory<AlgoShareBulkheadFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(AlgoShareBulkheadFilterFactory.class);

    // Bulkhead state with TTL cleanup
    private final ConcurrentHashMap<String, BulkheadState> bulkheads = new ConcurrentHashMap<>();

    public AlgoShareBulkheadFilterFactory() {
        super(Config.class);
        startCleanupTask();
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String requestId = exchange.getRequest().getId();
            String bulkheadName = config.getName();

            BulkheadState state = bulkheads.computeIfAbsent(bulkheadName,
                    k -> new BulkheadState(config));

            if (state.tryAdmit()) {
                log.debug("[BULKHEAD_ADMIT] {} RequestId={} Concurrent={}/{}",
                        bulkheadName, requestId, state.currentConcurrent.get(), config.getMaxConcurrent());

                return chain.filter(exchange)
                        .doFinally(signalType -> {
                            state.release();
                            log.debug("[BULKHEAD_RELEASE] {} RequestId={} Concurrent={}/{}",
                                    bulkheadName, requestId, state.currentConcurrent.get(), config.getMaxConcurrent());
                        });
            }

            // Queueing (if enabled)
            if (config.getQueueCapacity() > 0) {
                return state.tryQueue(requestId, config)
                        .flatMap(shouldProceed -> shouldProceed ?
                                chain.filter(exchange).doFinally(signal -> state.release()) :
                                handleBulkheadFull(exchange, config, "QUEUE_FULL"));
            }

            return handleBulkheadFull(exchange, config, "CAPACITY_EXCEEDED");
        };
    }

    /**
     *  Bulkhead state management
     */
    private static class BulkheadState {
        final AtomicInteger currentConcurrent = new AtomicInteger(0);
        final AtomicInteger totalAdmitted = new AtomicInteger(0);
        final AtomicInteger totalRejected = new AtomicInteger(0);
        final AtomicReference<Long> lastAccess = new AtomicReference<>(System.currentTimeMillis());
        private final AtomicInteger queuedRequests = new AtomicInteger(0);
        private final Config config;  // Store config reference

        BulkheadState(Config config) {
            this.config = config;
        }

        /**
         * Atomic tryAdmit() - prevents race conditions
         */
        boolean tryAdmit() {
            int current = currentConcurrent.get();
            if (current >= config.getMaxConcurrent()) {
                return false;
            }
            // Atomic increment with compare-and-set
            return currentConcurrent.compareAndSet(current, current + 1);
        }

        void release() {
            currentConcurrent.updateAndGet(n -> Math.max(0, n - 1));
            lastAccess.set(System.currentTimeMillis());
        }

        Mono<Boolean> tryQueue(String requestId, Config config) {
            // Check if queue has space
            if (queuedRequests.get() >= config.getQueueCapacity()) {
                log.debug("[BULKHEAD_QUEUE_FULL] {}", requestId);
                return Mono.just(false);  // Reject immediately
            }

            // Enter queue (non-blocking)
            queuedRequests.incrementAndGet();
            log.debug("[BULKHEAD_ENTER_QUEUE] {} QueueLength={}/{}",
                    requestId, queuedRequests.get(), config.getQueueCapacity());

            // Wait for slot OR timeout
            return Mono.defer(() -> {
                // Poll for available slot every 100ms
                return Mono.delay(Duration.ofMillis(100))
                        .repeatWhenEmpty(flux -> flux.delayElements(Duration.ofMillis(100)))
                        .timeout(Duration.ofMillis(config.getQueueTimeoutMs()))
                        .handle((signal, sink) -> {
                            if (tryAdmit()) {  // Slot available!
                                sink.next(true);
                                sink.complete();
                            }
                        })
                        .hasElement()
                        .doFinally(signal -> {
                            queuedRequests.decrementAndGet();
                            if (signal == SignalType.ON_ERROR || signal == SignalType.CANCEL) {
                                log.debug("[BULKHEAD_QUEUE_TIMEOUT] {}", requestId);
                            }
                        });
            });
        }
    }

    private Mono<Void> handleBulkheadFull(ServerWebExchange exchange, Config config, String reason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().add("Content-Type", "application/json");
        response.getHeaders().add("X-Bulkhead-Name", config.getName());
        response.getHeaders().add("X-Bulkhead-Reason", reason);

        BulkheadState state = bulkheads.get(config.getName());
        int concurrent = state != null ? state.currentConcurrent.get() : -1;
        int rejected = state != null ? state.totalRejected.incrementAndGet() : -1;

        String errorResponse = String.format("""
            {
                "error": "BULKHEAD_REJECTED",
                "message": "Service capacity exceeded",
                "bulkhead": "%s",
                "reason": "%s",
                "concurrent": %d,
                "maxConcurrent": %d,
                "totalRejected": %d
            }
            """, config.getName(), reason, concurrent, config.getMaxConcurrent(), rejected);

        DataBuffer buffer = response.bufferFactory()
                .wrap(errorResponse.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Memory leak protection - cleanup idle bulkheads
     */
    private void startCleanupTask() {
        Mono.delay(Duration.ofMinutes(5))
                .repeatWhen(flux -> flux.delayElements(Duration.ofMinutes(5)))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(time -> {
                    long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
                    int removed = (int) bulkheads.entrySet().stream()
                            .filter(entry -> entry.getValue().lastAccess.get() < cutoff)
                            .peek(entry -> bulkheads.remove(entry.getKey()))
                            .count();
                    if (removed > 0) {
                        log.info("[BULKHEAD_CLEANUP] Removed {} idle bulkheads", removed);
                    }
                });
    }

    public static class Config {
        private String name = "default";
        private int maxConcurrent = 50;
        private int queueCapacity = 10;
        private long queueTimeoutMs = 2000;

        // Getters/Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public long getQueueTimeoutMs() { return queueTimeoutMs; }
        public void setQueueTimeoutMs(long queueTimeoutMs) { this.queueTimeoutMs = queueTimeoutMs; }
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList("name", "maxConcurrent", "queueCapacity");
    }
}
