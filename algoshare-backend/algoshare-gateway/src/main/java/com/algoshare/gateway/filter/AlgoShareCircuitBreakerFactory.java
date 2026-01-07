package com.algoshare.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

/**
 * High-Performance Circuit Breaker for AlgoShare Trading Platform
 *
 * ------------------------------------------------------------------------------------------------------
 * INTERVIEW DEEP DIVE: Circuit Breaker Pattern (Resilience)
 * ------------------------------------------------------------------------------------------------------
 * QA: "How does a Circuit Breaker actually protect the system?"
 *
 * 1. State Machine Mechanics:
 *    - CLOSED: Normal operation. Requests pass through. Activity is monitored.
 *    - OPEN: Failure threshold reached (e.g., 50% errors or 200ms+ latency).
 *            IMMEDIATE FAST FAIL. No request is sent to LLM/Strategy.
 *            Returns '503 Service Unavailable' or executes 'fallbackUri'.
 *    - HALF-OPEN: After 'resetTimeout' (e.g., 30s), we let a FEW requests through (probing).
 *                 If they succeed -> Reset to CLOSED. If fail -> Back to OPEN.
 *
 * 2. Resource Protection (The "Why"):
 *    - Without CB: A slow AI service holds open a Servlet Thread/Netty Connection for 5s.
 *    - If you have 500 threads and 1000 requests come in, ALL threads get stuck waiting.
 *    - Result: The Gateway crashes (Thread Starvation). You can't even serve a static endpoint.
 *    - With CB: The Gateway kills the request in <1ms (Fast Fail). Threads remain free.
 *
 * 3. HFT Optimization (Custom "Lock-Free" Logic):
 *    - Why not Resilience4j? It uses synchronized Maps/Sliding Windows which add micro-latency.
 *    - We use `AtomicInteger` & `AtomicReference` (CAS operations) to track state without locking threads.
 *    - We track "Slow Calls" (>200ms) aggressively to prevent "Death Spirals" in the Strategy Service.
 * ------------------------------------------------------------------------------------------------------
 *
 * ------------------------------------------------------------------------------------------------------
 * Q: "Why is this called a 'Factory'?" (Factory Pattern)
 * ------------------------------------------------------------------------------------------------------
 * The Factory Pattern means: "A class that creates other objects".
 *
 * 1. Direct vs Factory:
 *    // Not Factory (creates directly)
 *    CircuitBreaker cb = new CircuitBreaker();
 *
 *    // Factory Pattern (factory creates it)
 *    CircuitBreaker cb = CircuitBreakerFactory.create(config);
 *
 * 2. How Spring Cloud Gateway uses it:
 *    AlgoShareCircuitBreakerFactory  -> Creates GatewayFilter instances
 *                                    -> Each filter has different config (per route)
 *
 * 3. Step-by-Step Flow:
 *    a. Spring Scans: Finds this @Component bean.
 *    b. Registers: As filter name "AlgoShareCircuitBreaker" (Removes "Factory" suffix).
 *    c. Configures: Reads YAML (args: name, failureThreshold).
 *    d. Calls apply(Config): Creates the specific GatewayFilter instance for that route.
 *
 * 4. Visual Flow:
 *    ┌───────────────────────┐             ┌──────────────────────────────────┐
 *    │  application.yml      │             │  AlgoShareCircuitBreakerFactory  │
 *    │  -------------------  │             │  ------------------------------  │
 *    │  filters:             │   MAPPED    │  @Override                       │
 *    │    - Name: order-cb   │ ──────────► │  apply(Config config) {          │
 *    │      Threshold: 3     │             │     return new Filter(config);   │
 *    │                       │             │  }                               │
 *    └───────────────────────┘             └───────────────┬──────────────────┘
 *                                                          │ Creates
 *                                                          ▼
 *                                          ┌──────────────────────────────────┐
 *                                          │  GatewayFilter Instance          │
 *                                          │  ----------------------          │
 *                                          │  Config: {name="order-cb"}       │
 *                                          │  State: CLOSED                   │
 *                                          └──────────────────────────────────┘
 * ------------------------------------------------------------------------------------------------------
 *
 * ------------------------------------------------------------------------------------------------------
 * INTERVIEW STRATEGY: Circuit Breaker Design (7-Step Guide)
 * ------------------------------------------------------------------------------------------------------
 * STEP 1: Clarify Requirements (2-3 mins)
 * - System: Microservices Gateway (AlgoShare)
 * - Failures: Network timeouts, overload, database deadlocks
 * - Traffic: 5000+ TPS, <200ms latency requirement
 *
 * STEP 2: Define Core Concepts (State Machine)
 *    CLOSED (Normal)
 *       │ (Failures >= threshold?)
 *       ▼
 *    OPEN (Blocking)
 *       │ (Timeout elapsed?)
 *       ▼
 *    HALF_OPEN (Testing)
 *       │─ (Success?) ──> Back to CLOSED
 *       └─ (Failure?) ──> Back to OPEN
 *
 * STEP 3: Design Configuration
 * - name: "order-cb" (Identity)
 * - failureThreshold: 5 (Trip point)
 * - resetTimeout: 30s (Recovery testing delay)
 * - slidingWindowSize: 20 (Failure rate accuracy)
 * - slowCallThreshold: 200ms (Latency-aware failure)
 * - halfOpenPermits: 3 (Prevent thundering herd)
 *
 * STEP 4: Design State Management (Concurrency)
 * - Challenge: High throughput without locks.
 * - Solution: "AtomicReference<State>" instead of "synchronized".
 * - Metrics: AtomicInteger counters (safe for multi-threading).
 * - Benefit: CAS (Compare-And-Swap) allows 10,000+ TPS vs 100 with locks.
 *
 * STEP 5: Design Sliding Window (Ring Buffer)
 * - Why? To calculate failure rate accurately over the last N requests.
 * - Structure: Fixed-size circular array (LockFreeRingBuffer).
 * - [X, X, V, V, X] (writeIndex moves cleanly around).
 * - Properties: O(1) insertion, fixed memory, no GC pressure.
 *
 * STEP 6: State Transition Logic
 * - CLOSED -> OPEN: If (Count >= Threshold AND Rate >= 50%) OR (SlowCalls >= Threshold)
 * - OPEN -> HALF_OPEN: After resetTimeout.
 * - HALF_OPEN -> CLOSED: k consecutive successes.
 * - HALF_OPEN -> OPEN: Any failure blocks immediately.
 *
 * STEP 7: Integration Design
 * - Place as a GatewayFilter.
 * - Logic:
 *   1. Check State (Fast Fail if OPEN)
 *   2. Execute Request (Measure Latency)
 *   3. Record Result (Success/Failure/SlowCall)
 *
 * INTERVIEW SUMMARY (Recap):
 * 1. STATE MACHINE: 3 states to prevent cascading failures.
 * 2. CONFIG: Latency-aware thresholds (HFT requirement).
 * 3. LOCK-FREE: AtomicReference/CAS for performance.
 * 4. SLIDING WINDOW: Ring Buffer for memory efficiency.
 * 5. HALF-OPEN CONTROL: Limits probing traffic.
 * ------------------------------------------------------------------------------------------------------
 *
 * @author AlgoShare Team
 */
@Component
public class AlgoShareCircuitBreakerFactory extends AbstractGatewayFilterFactory<AlgoShareCircuitBreakerFactory.Config> {

    private final ConcurrentHashMap<String, CircuitBreakerState> circuitBreakers = new ConcurrentHashMap<>();

    public AlgoShareCircuitBreakerFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String circuitName = config.getName();
            CircuitBreakerState state = circuitBreakers.computeIfAbsent(
                    circuitName,
                    k -> new CircuitBreakerState(config)
            );

            // Fast-fail if circuit is OPEN
            if (state.isOpen()) {
                return handleCircuitOpen(exchange, config, state);
            }

            long startTime = System.nanoTime();

            // Execute with circuit breaker protection
            return chain.filter(exchange)
                    .doOnSuccess(v -> {
                        long latency = (System.nanoTime() - startTime) / 1_000_000;
                        state.recordSuccess(latency);
                    })
                    .doOnError(throwable -> {
                        long latency = (System.nanoTime() - startTime) / 1_000_000;
                        state.recordFailure(latency);
                    })
                    .onErrorResume(throwable -> handleError(exchange, config, throwable, state));
        };
    }

    private Mono<Void> handleCircuitOpen(ServerWebExchange exchange, Config config, CircuitBreakerState state) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().add("Content-Type", "application/json");
        response.getHeaders().add("Retry-After", String.valueOf(config.getResetTimeout() / 1000));
        response.getHeaders().add("X-Circuit-State", "OPEN");
        response.getHeaders().add("X-Circuit-Name", config.getName());

        String errorResponse = String.format("""
                        {
                            "error": "SERVICE_UNAVAILABLE",
                            "message": "Circuit breaker is OPEN. Service temporarily unavailable.",
                            "circuit": "%s",
                            "state": "%s",
                            "failures": %d,
                            "retryAfter": %d,
                            "timestamp": %d
                        }
                        """,
                config.getName(),
                state.getCurrentState(),
                state.getFailureCount(),
                config.getResetTimeout() / 1000,
                System.currentTimeMillis());

        DataBuffer buffer = response.bufferFactory().wrap(errorResponse.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> handleError(ServerWebExchange exchange, Config config, Throwable throwable, CircuitBreakerState state) {
        if (config.getFallbackUri() != null && !config.getFallbackUri().isEmpty()) {
            return executeFallback(exchange, config);
        }

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.BAD_GATEWAY);
        response.getHeaders().add("Content-Type", "application/json");
        response.getHeaders().add("X-Circuit-State", state.getCurrentState().toString());

        String errorResponse = String.format("""
                        {
                            "error": "UPSTREAM_ERROR",
                            "message": "Service request failed",
                            "details": "%s",
                            "circuit": "%s",
                            "timestamp": %d
                        }
                        """,
                throwable.getMessage(),
                config.getName(),
                System.currentTimeMillis());

        DataBuffer buffer = response.bufferFactory().wrap(errorResponse.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> executeFallback(ServerWebExchange exchange, Config config) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().add("Content-Type", "application/json");
        response.getHeaders().add("X-Fallback-Response", "true");

        String fallbackResponse = """
                {
                    "status": "DEGRADED",
                    "message": "Using cached data due to service unavailability",
                    "mode": "fallback"
                }
                """;

        DataBuffer buffer = response.bufferFactory().wrap(fallbackResponse.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList("name", "failureThreshold", "resetTimeout");
    }

    public static class Config {
        private String name = "default";
        private int failureThreshold = 5;
        private long resetTimeout = 30000;
        private int successThreshold = 2;
        private int slidingWindowSize = 20;
        private long slowCallThreshold = 200;
        private int halfOpenPermits = 3;
        private String fallbackUri;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getResetTimeout() {
            return resetTimeout;
        }

        public void setResetTimeout(long resetTimeout) {
            this.resetTimeout = resetTimeout;
        }

        public int getSuccessThreshold() {
            return successThreshold;
        }

        public void setSuccessThreshold(int successThreshold) {
            this.successThreshold = successThreshold;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public long getSlowCallThreshold() {
            return slowCallThreshold;
        }

        public void setSlowCallThreshold(long slowCallThreshold) {
            this.slowCallThreshold = slowCallThreshold;
        }

        public int getHalfOpenPermits() {
            return halfOpenPermits;
        }

        public void setHalfOpenPermits(int halfOpenPermits) {
            this.halfOpenPermits = halfOpenPermits;
        }

        public String getFallbackUri() {
            return fallbackUri;
        }

        public void setFallbackUri(String fallbackUri) {
            this.fallbackUri = fallbackUri;
        }
    }

    private static class CircuitBreakerState {
        private enum State {CLOSED, OPEN, HALF_OPEN}

        private final Config config;
        private final AtomicReference<State> currentState = new AtomicReference<>(State.CLOSED);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger slowCallCount = new AtomicInteger(0);
        private final AtomicLong lastFailureTime = new AtomicLong(0);
        private final AtomicInteger halfOpenPermitsUsed = new AtomicInteger(0);
        private final LockFreeRingBuffer slidingWindow;

        public CircuitBreakerState(Config config) {
            this.config = config;
            this.slidingWindow = new LockFreeRingBuffer(config.getSlidingWindowSize());
        }

        public boolean isOpen() {
            State state = currentState.get();

            if (state == State.OPEN) {
                long timeSinceFailure = System.currentTimeMillis() - lastFailureTime.get();
                if (timeSinceFailure >= config.getResetTimeout()) {
                    if (currentState.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        successCount.set(0);
                        halfOpenPermitsUsed.set(0);
                    }
                    return false;
                }
                return true;
            }

            if (state == State.HALF_OPEN) {
                int permitsUsed = halfOpenPermitsUsed.get();
                if (permitsUsed >= config.getHalfOpenPermits()) {
                    return true;
                }
                halfOpenPermitsUsed.incrementAndGet();
            }

            return false;
        }

        public void recordSuccess(long latencyMs) {
            slidingWindow.add(latencyMs < config.getSlowCallThreshold());

            State state = currentState.get();

            if (state == State.HALF_OPEN) {
                int successes = successCount.incrementAndGet();
                if (successes >= config.getSuccessThreshold()) {
                    if (currentState.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                        failureCount.set(0);
                        successCount.set(0);
                        slowCallCount.set(0);
                    }
                }
            } else if (state == State.CLOSED) {
                failureCount.set(0);
            }
        }

        public void recordFailure(long latencyMs) {
            slidingWindow.add(false);
            lastFailureTime.set(System.currentTimeMillis());

            State state = currentState.get();

            if (state == State.HALF_OPEN) {
                if (currentState.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                    failureCount.set(0);
                    successCount.set(0);
                }
            } else if (state == State.CLOSED) {
                int failures = failureCount.incrementAndGet();

                if (latencyMs >= config.getSlowCallThreshold()) {
                    slowCallCount.incrementAndGet();
                }

                double failureRate = slidingWindow.getFailureRate();

                if ((failures >= config.getFailureThreshold() && failureRate >= 0.5) ||
                        (slowCallCount.get() >= config.getFailureThreshold())) {
                    currentState.compareAndSet(State.CLOSED, State.OPEN);
                }
            }
        }

        public State getCurrentState() {
            return currentState.get();
        }

        public int getFailureCount() {
            return failureCount.get();
        }
    }

    private static class LockFreeRingBuffer {
        private final AtomicReferenceArray<Boolean> buffer;
        private final AtomicInteger writeIndex = new AtomicInteger(0);
        private final AtomicInteger size = new AtomicInteger(0);
        private final int capacity;

        public LockFreeRingBuffer(int capacity) {
            this.capacity = capacity;
            this.buffer = new AtomicReferenceArray<>(capacity);
        }

        public void add(boolean success) {
            int index = writeIndex.getAndUpdate(i -> (i + 1) % capacity);
            buffer.set(index, success);
            size.updateAndGet(s -> Math.min(s + 1, capacity));
        }

        public double getFailureRate() {
            int currentSize = size.get();
            if (currentSize == 0) return 0.0;

            int failures = 0;
            for (int i = 0; i < currentSize; i++) {
                Boolean result = buffer.get(i);
                if (result != null && !result) {
                    failures++;
                }
            }
            return (double) failures / currentSize;
        }

        public int getSize() {
            return size.get();
        }
    }
}