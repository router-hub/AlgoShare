package com.algoshare.auth.domain.service;

import com.algoshare.auth.domain.model.EventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.algoshare.auth.domain.constants.AuthConstants.USER_EVENTS_TOPIC;

/**
 * Event Publisher Service - Kafka event publishing
 * Handles asynchronous event publishing without blocking registration flow
 */
@Service
@Slf4j
public class EventPublisherService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public EventPublisherService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publish USER_REGISTERED event
     * Contains userId, email, and raw token for email sending
     * 
     * Note: This is async - registration doesn't fail if publishing fails
     */
    public void publishUserRegistered(UUID userId, String email, String rawToken) {
        log.info("[EVENT_PUBLISH_START] Publishing USER_REGISTERED event | userId={} | email={} | topic={}", 
            userId, email, USER_EVENTS_TOPIC);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("eventType", EventType.USER_REGISTERED.toString());
        eventData.put("userId", userId.toString());
        eventData.put("email", email);
        eventData.put("verificationToken", rawToken);  // Raw token for email
        eventData.put("timestamp", System.currentTimeMillis());

        // Send asynchronously
        CompletableFuture<SendResult<String, Object>> future = 
            kafkaTemplate.send(USER_EVENTS_TOPIC, userId.toString(), eventData);

        // Handle success/failure asynchronously (non-blocking)
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("[EVENT_PUBLISHED] USER_REGISTERED event published successfully | userId={} | topic={} | partition={} | offset={}", 
                    userId, USER_EVENTS_TOPIC, 
                    result.getRecordMetadata().partition(), 
                    result.getRecordMetadata().offset());
            } else {
                log.warn("[EVENT_PUBLISH_FAILED] Failed to publish USER_REGISTERED event | userId={} | topic={} | error={}",
                    userId, USER_EVENTS_TOPIC, ex.getMessage(), ex);
                
                // TODO: Implement retry logic or dead letter queue
                // For now, just log - registration already succeeded
            }
        });
    }

    /**
     * Publish LOGIN_OTP_REQUESTED event (for future login flow)
     */
    public void publishOtpRequested(UUID userId, String email, String sessionId) {
        log.info("[EVENT_PUBLISH_START] Publishing LOGIN_OTP_REQUESTED event | userId={} | sessionId={}", 
            userId, sessionId);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("eventType", EventType.LOGIN_OTP_REQUESTED.toString());
        eventData.put("userId", userId.toString());
        eventData.put("email", email);
        eventData.put("sessionId", sessionId);
        eventData.put("timestamp", System.currentTimeMillis());

        kafkaTemplate.send("auth.events", userId.toString(), eventData)
            .whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[EVENT_PUBLISHED] LOGIN_OTP_REQUESTED event published | userId={} | sessionId={}", 
                        userId, sessionId);
                } else {
                    log.error("[EVENT_PUBLISH_FAILED] Failed to publish LOGIN_OTP_REQUESTED event | userId={} | error={}", 
                        userId, ex.getMessage(), ex);
                }
            });
    }
}
