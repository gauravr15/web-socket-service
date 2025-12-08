package com.odin.web_socket_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odin.web_socket_service.dto.SendMessageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing offline messages stored in Redis.
 * Handles storage and retrieval of undelivered messages.
 * Configurable storage enable/disable and TTL settings.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineMessageService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${offline.message.storage.enabled:true}")
    private boolean offlineMessageStorageEnabled;

    @Value("${offline.message.ttl.days:30}")
    private int messageRetentionDays;

    private static final String UNDELIVERED_MESSAGE_KEY_PREFIX = "websocket:undelivered:";

    /**
     * Store an undelivered message in Redis with configurable TTL.
     * Key format: websocket:undelivered:{receiverId}
     * Value: JSON serialized SendMessageResponse
     */
    public void storeUndeliveredMessage(String receiverId, SendMessageResponse message) {
        try {
            if (!offlineMessageStorageEnabled) {
                log.debug("Offline message storage is disabled via offline.message.storage.enabled configuration");
                return;
            }

            if (receiverId == null || message == null) {
                log.warn("storeUndeliveredMessage: Invalid inputs - receiverId={}, message={}", receiverId, message);
                return;
            }

            String key = buildUndeliveredMessageKey(receiverId);
            String messageJson = objectMapper.writeValueAsString(message);
            
            // Store as a hash with messageId as field for easy management
            String messageId = message.getMessageId();
            if (messageId == null || messageId.isEmpty()) {
                log.warn("storeUndeliveredMessage: Message ID is missing for receiver={}", receiverId);
                return;
            }

            // Use Redis hash to store multiple messages per receiver
            // Format: websocket:undelivered:{receiverId} -> {messageId: messageJson, ...}
            redisTemplate.opsForHash().put(key, messageId, messageJson);
            
            // Set expiration on the key
            redisTemplate.expire(key, Duration.ofDays(messageRetentionDays));
            
            log.info("Stored undelivered message for receiver={}, messageId={}, ttl={}days", 
                    receiverId, messageId, messageRetentionDays);
        } catch (Exception e) {
            log.error("Failed to store undelivered message for receiver={}: {}", receiverId, e.getMessage(), e);
        }
    }

    /**
     * Retrieve all undelivered messages for a receiver from Redis.
     * Returns a list of SendMessageResponse objects.
     */
    public List<SendMessageResponse> getUndeliveredMessages(String receiverId) {
        try {
            if (receiverId == null || receiverId.isEmpty()) {
                log.warn("getUndeliveredMessages: Invalid receiverId={}", receiverId);
                return new ArrayList<>();
            }

            String key = buildUndeliveredMessageKey(receiverId);
            var messagesMap = redisTemplate.opsForHash().entries(key);

            if (messagesMap == null || messagesMap.isEmpty()) {
                log.debug("No undelivered messages found for receiver={}", receiverId);
                return new ArrayList<>();
            }

            List<SendMessageResponse> messages = new ArrayList<>();
            for (var entry : messagesMap.entrySet()) {
                try {
                    String messageJson = (String) entry.getValue();
                    SendMessageResponse message = objectMapper.readValue(messageJson, SendMessageResponse.class);
                    messages.add(message);
                    log.debug("Retrieved undelivered message for receiver={}, messageId={}", 
                            receiverId, entry.getKey());
                } catch (Exception e) {
                    log.error("Failed to deserialize message for receiver={}, messageId={}: {}", 
                            receiverId, entry.getKey(), e.getMessage(), e);
                    // Continue processing other messages
                }
            }

            log.info("Retrieved {} undelivered messages for receiver={}", messages.size(), receiverId);
            return messages;
        } catch (Exception e) {
            log.error("Failed to retrieve undelivered messages for receiver={}: {}", receiverId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Delete undelivered messages for a receiver after they have been delivered.
     */
    public void deleteUndeliveredMessages(String receiverId) {
        try {
            if (receiverId == null || receiverId.isEmpty()) {
                log.warn("deleteUndeliveredMessages: Invalid receiverId={}", receiverId);
                return;
            }

            String key = buildUndeliveredMessageKey(receiverId);
            Boolean deleted = redisTemplate.delete(key);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("Deleted all undelivered messages for receiver={}", receiverId);
            } else {
                log.debug("No undelivered messages found to delete for receiver={}", receiverId);
            }
        } catch (Exception e) {
            log.error("Failed to delete undelivered messages for receiver={}: {}", receiverId, e.getMessage(), e);
        }
    }

    /**
     * Delete a specific undelivered message by messageId.
     */
    public void deleteUndeliveredMessage(String receiverId, String messageId) {
        try {
            if (receiverId == null || messageId == null) {
                log.warn("deleteUndeliveredMessage: Invalid inputs - receiverId={}, messageId={}", receiverId, messageId);
                return;
            }

            String key = buildUndeliveredMessageKey(receiverId);
            Long deleted = redisTemplate.opsForHash().delete(key, messageId);

            if (deleted != null && deleted > 0) {
                log.debug("Deleted undelivered message for receiver={}, messageId={}", receiverId, messageId);
            }
        } catch (Exception e) {
            log.error("Failed to delete specific undelivered message - receiver={}, messageId={}: {}", 
                    receiverId, messageId, e.getMessage(), e);
        }
    }

    /**
     * Check if a receiver has any undelivered messages.
     */
    public boolean hasUndeliveredMessages(String receiverId) {
        try {
            if (receiverId == null || receiverId.isEmpty()) {
                return false;
            }

            String key = buildUndeliveredMessageKey(receiverId);
            Boolean hasKey = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(hasKey);
        } catch (Exception e) {
            log.error("Failed to check undelivered messages for receiver={}: {}", receiverId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Build the Redis key for undelivered messages.
     */
    private String buildUndeliveredMessageKey(String receiverId) {
        return UNDELIVERED_MESSAGE_KEY_PREFIX + receiverId;
    }
}
