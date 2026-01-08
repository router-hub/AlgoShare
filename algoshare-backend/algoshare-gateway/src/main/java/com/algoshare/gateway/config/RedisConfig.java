package com.algoshare.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

/**
 * Redis Configuration for Distributed Rate Limiting
 *
 * ------------------------------------------------------------------------------------------------------
 * INTERVIEW QA: "Why do we need a separate RedisConfig class?"
 * ------------------------------------------------------------------------------------------------------
 * A: "Spring Boot Auto-Configuration provides basic Redis setup, but we need CUSTOM configurations:
 *     1. ReactiveRedisTemplate (not the default blocking RedisTemplate)
 *     2. Lua Script loading for atomic rate limiting operations
 *     3. Custom serialization (String serializer for both keys and values)"
 *
 * ------------------------------------------------------------------------------------------------------
 * DEEP DIVE 1: Why ReactiveRedisTemplate?
 * ------------------------------------------------------------------------------------------------------
 * Spring Cloud Gateway is built on Spring WebFlux (Reactive/Non-Blocking).
 *
 * Problem with Default RedisTemplate:
 * - RedisTemplate is BLOCKING (uses Jedis/Lettuce in sync mode).
 * - If we use it in Gateway, it BLOCKS the Netty Event Loop thread.
 * - Netty has ~10-20 threads. If 20 requests wait for Redis (50ms each), the Gateway freezes.
 *
 * Solution: ReactiveRedisTemplate
 * - Built on Lettuce's Reactive API (Project Reactor).
 * - Returns Mono<T> and Flux<T> instead of T.
 * - The thread makes the Redis call, DOESN'T WAIT, moves on to serve other requests.
 * - When Redis responds, the callback is executed (Event-loop pattern).
 *
 * Interview Example:
 * Q: "What happens if 1000 requests hit the Gateway and each needs Redis?"
 * A: "With Reactive:
 *     - All 1000 Redis calls are dispatched immediately (non-blocking).
 *     - The 10 Netty threads handle OTHER work while waiting.
 *     - When Redis responses arrive, they're processed via callbacks.
 *     With Blocking:
 *     - Thread 1 makes call, WAITS 50ms, then Thread 2, etc.
 *     - After 10 threads are blocked, request 11 has to WAIT.
 *     - Throughput drops from 1000 req/sec to ~200 req/sec."
 *
 * ------------------------------------------------------------------------------------------------------
 * DEEP DIVE 2: Why Custom Serialization?
 * ------------------------------------------------------------------------------------------------------
 * Redis stores BYTES, not Java objects. We need to tell Spring:
 * - How to convert Java String -> Bytes (Serialization)
 * - How to convert Bytes -> Java String (Deserialization)
 *
 * Options:
 * 1. JdkSerializationRedisSerializer (Default) - BAD
 *    - Stores full Java class metadata.
 *    - Key "user:123" becomes 50+ bytes of gibberish in Redis.
 *    - Can't inspect data using `redis-cli`.
 *
 * 2. StringRedisSerializer - GOOD
 *    - Direct String <-> UTF-8 bytes.
 *    - Key "user:123" is stored as exactly "user:123".
 *    - Human-readable in `redis-cli`.
 *
 * We configure:
 * - .key(serializer) -> Keys are Strings
 * - .value(serializer) -> Values are Strings
 * - .hashKey/.hashValue -> Hash fields are Strings
 *
 * ------------------------------------------------------------------------------------------------------
 * DEEP DIVE 3: Why Lua Script?
 * ------------------------------------------------------------------------------------------------------
 * The Token Bucket algorithm needs ATOMIC operations:
 * 1. Read current tokens
 * 2. Calculate refill
 * 3. Check if request allowed
 * 4. Decrement tokens
 * 5. Update last_refill timestamp
 *
 * Problem with Multiple Commands:
 * - If Gateway Instance A and B both read 10 tokens at the same time,
 * - Both think they have tokens available,
 * - Both decrement -> Result: -1 tokens (RACE CONDITION).
 *
 * Solution: Lua Script in Redis
 * - Redis executes the ENTIRE script atomically.
 * - While script runs, NO other Redis commands can interleave.
 * - Guarantees consistency in distributed systems.
 *
 * Interview QA:
 * Q: "Why not use Redis Transactions (MULTI/EXEC)?"
 * A: "MULTI/EXEC doesn't support conditional logic.
 *     We need 'if tokens >= requested then decrement else reject'.
 *     Lua scripts allow full programming logic within atomic execution."
 *
 * ------------------------------------------------------------------------------------------------------
 * SUMMARY (30-Second Answer):
 * "RedisConfig exists because:
 *  1. We need ReactiveRedisTemplate (non-blocking for WebFlux).
 *  2. We customize serialization for human-readable keys.
 *  3. We load a Lua script for atomic rate limiting (prevents race conditions)."
 * ------------------------------------------------------------------------------------------------------
 *
 * @author Aditya
 */
@Configuration
public class RedisConfig {

    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {

        StringRedisSerializer serializer = new StringRedisSerializer();

        RedisSerializationContext<String, String> serializationContext =
                RedisSerializationContext.<String, String>newSerializationContext()
                        .key(serializer)
                        .value(serializer)
                        .hashKey(serializer)
                        .hashValue(serializer)
                        .build();

        return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
    }

    @Bean
    public RedisScript<List> rateLimiterScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/rate_limiter.lua"),
                List.class
        );
    }
}