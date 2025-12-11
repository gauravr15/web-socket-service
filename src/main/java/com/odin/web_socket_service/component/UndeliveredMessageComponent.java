package com.odin.web_socket_service.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odin.web_socket_service.dto.SendMessageResponse;
import com.odin.web_socket_service.service.IUndeliveredMessageService;
import com.odin.web_socket_service.utility.NotificationUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Component for managing undelivered text messages in Redis.
 * Implements core business logic for storing and retrieving offline messages.
 * Also publishes notifications to Kafka when messages are stored.
 * 
 * Key Structure in Redis:
 * - Key: websocket:undelivered:{receiverId}
 * - Field-Value: messageId -> JSON(SendMessageResponse)
 * 
 * This uses Redis Hash to support multiple undelivered messages per receiver.
 * Each message is stored as a field in the hash, allowing selective deletion.
 * 
 * Kafka Integration:
 * - Topic: undelivered.notification.message
 * - When message is stored in Redis, notification is published to Kafka
 * - Notification Service consumes and processes the message
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UndeliveredMessageComponent implements IUndeliveredMessageService {

    private final StringRedisTemplate redisTemplate;
    private final NotificationUtility notificationUtility;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${offline.message.storage.enabled:true}")
    private boolean offlineMessageStorageEnabled;

    @Value("${offline.message.ttl.days:30}")
    private int messageRetentionDays;

    private static final String UNDELIVERED_MESSAGE_KEY_PREFIX = "websocket:undelivered:";
    private static final String LOG_PREFIX = "[UNDELIVERED-MESSAGE-COMPONENT]";

    /**
     * Store an undelivered message in Redis with automatic TTL expiration.
     * Also publishes a notification to Kafka for the notification service to consume.
     * 
     * Called when a text message cannot be delivered because receiver is offline.
     * 
     * Flow:
     * 1. Message is stored in Redis with TTL
     * 2. Notification is published to Kafka topic "undelivered.notification.message"
     * 3. Notification Service consumes and handles notification
     * 
     * Redis Structure:
     * HSET websocket:undelivered:receiverId messageId jsonPayload
     * EXPIRE websocket:undelivered:receiverId 2592000 (30 days in seconds)
     * 
     * @param receiverId The ID of the message receiver
     * @param message The SendMessageResponse containing full message details
     */
    @Override
    public void storeUndeliveredMessage(String receiverId, SendMessageResponse message) {
        try {
            // Check if feature is enabled
            if (!offlineMessageStorageEnabled) {
                log.debug("{} Offline message storage is disabled via configuration", LOG_PREFIX);
                return;
            }

            // Validate inputs
            if (receiverId == null || receiverId.isEmpty()) {
                log.warn("{} Invalid receiverId provided: {}", LOG_PREFIX, receiverId);
                return;
            }

            if (message == null) {
                log.warn("{} Message object is null for receiverId={}", LOG_PREFIX, receiverId);
                return;
            }

            String messageId = message.getMessageId();
            if (messageId == null || messageId.isEmpty()) {
                log.warn("{} Message ID is missing for receiverId={}", LOG_PREFIX, receiverId);
                return;
            }

            // Build Redis key
            String redisKey = buildUndeliveredMessageKey(receiverId);

            // Serialize message to JSON
            String messageJson = objectMapper.writeValueAsString(message);

            // Store in Redis hash: HSET key field value
            redisTemplate.opsForHash().put(redisKey, messageId, messageJson);

            // Set expiration on the key
            redisTemplate.expire(redisKey, Duration.ofDays(messageRetentionDays));

            log.info("{} Stored undelivered message in Redis - receiverId={}, messageId={}, " +
                            "senderMobile={}, ttl={}days, keySize={}bytes",
                    LOG_PREFIX, receiverId, messageId,
                    message.getSenderMobile(), messageRetentionDays, messageJson.length());

            // Publish notification to Kafka for notification service
            notificationUtility.publishUndeliveredMessageNotification(receiverId, message);

        } catch (Exception e) {
            log.error("{} Failed to store undelivered message for receiverId={}: {}",
                    LOG_PREFIX, receiverId, e.getMessage(), e);
        }
    }

    /**
     * Retrieve all undelivered messages for a receiver from Redis.
     * Returns messages in the same format as SendMessageResponse for frontend consistency.
     * 
     * Frontend can immediately process these messages as if they were received via WebSocket.
     * Messages maintain full context: sender info, timestamp, message content, files, etc.
     */
    @Override
    public List<SendMessageResponse> getUndeliveredMessages(String receiverId) {
        List<SendMessageResponse> messages = new ArrayList<>();

        try {
            // Validate input
            if (receiverId == null || receiverId.isEmpty()) {
                log.warn("{} Invalid receiverId provided: {}", LOG_PREFIX, receiverId);
                return messages;
            }

            String redisKey = buildUndeliveredMessageKey(receiverId);

            // Retrieve all fields and values from the hash
            var messagesMap = redisTemplate.opsForHash().entries(redisKey);

            if (messagesMap == null || messagesMap.isEmpty()) {
                log.debug("{} No undelivered messages found for receiverId={}", LOG_PREFIX, receiverId);
                return messages;
            }

            // Deserialize each message from JSON
            for (var entry : messagesMap.entrySet()) {
                try {
                    String messageId = (String) entry.getKey();
                    String messageJson = (String) entry.getValue();

                    SendMessageResponse message = objectMapper.readValue(messageJson, SendMessageResponse.class);
                    messages.add(message);

                    log.debug("{} Deserialized undelivered message - receiverId={}, messageId={}, " +
                                    "senderMobile={}",
                            LOG_PREFIX, receiverId, messageId, message.getSenderMobile());

                } catch (Exception e) {
                    log.error("{} Failed to deserialize message - receiverId={}, messageId={}: {}",
                            LOG_PREFIX, receiverId, entry.getKey(), e.getMessage(), e);
                    // Continue processing other messages instead of failing entirely
                }
            }

            log.info("{} Retrieved {} undelivered message(s) for receiverId={}",
                    LOG_PREFIX, messages.size(), receiverId);

            return messages;

        } catch (Exception e) {
            log.error("{} Failed to retrieve undelivered messages for receiverId={}: {}",
                    LOG_PREFIX, receiverId, e.getMessage(), e);
            return messages;
        }
    }

    /**
     * Delete all undelivered messages for a receiver after they have been fetched.
     * Called by frontend after successfully processing undelivered messages.
     * 
     * This ensures one-time delivery - messages won't be fetched multiple times.
     */
    @Override
    public void deleteUndeliveredMessages(String receiverId) {
        try {
            if (receiverId == null || receiverId.isEmpty()) {
                log.warn("{} Invalid receiverId provided: {}", LOG_PREFIX, receiverId);
                return;
            }

            String redisKey = buildUndeliveredMessageKey(receiverId);

            // Delete the entire hash (all messages for this receiver)
            Boolean deleted = redisTemplate.delete(redisKey);

            if (Boolean.TRUE.equals(deleted)) {
                log.info("{} Deleted all undelivered messages for receiverId={}", LOG_PREFIX, receiverId);
            } else {
                log.debug("{} No undelivered messages found to delete for receiverId={}", 
                        LOG_PREFIX, receiverId);
            }

        } catch (Exception e) {
            log.error("{} Failed to delete undelivered messages for receiverId={}: {}",
                    LOG_PREFIX, receiverId, e.getMessage(), e);
        }
    }

    /**
     * Delete a specific undelivered message by messageId.
     * Useful if receiver wants to delete a single message without clearing all.
     */
    @Override
    public void deleteUndeliveredMessage(String receiverId, String messageId) {
        try {
            if (receiverId == null || receiverId.isEmpty()) {
                log.warn("{} Invalid receiverId provided: {}", LOG_PREFIX, receiverId);
                return;
            }

            if (messageId == null || messageId.isEmpty()) {
                log.warn("{} Invalid messageId provided: {}", LOG_PREFIX, messageId);
                return;
            }

            String redisKey = buildUndeliveredMessageKey(receiverId);

            // Delete a specific field from the hash
            Long deletedCount = redisTemplate.opsForHash().delete(redisKey, messageId);

            if (deletedCount != null && deletedCount > 0) {
                log.info("{} Deleted specific undelivered message - receiverId={}, messageId={}",
                        LOG_PREFIX, receiverId, messageId);
            } else {
                log.debug("{} Message not found or already deleted - receiverId={}, messageId={}",
                        LOG_PREFIX, receiverId, messageId);
            }

        } catch (Exception e) {
            log.error("{} Failed to delete specific undelivered message - receiverId={}, messageId={}: {}",
                    LOG_PREFIX, receiverId, messageId, e.getMessage(), e);
        }
    }

    /**
     * Check if a receiver has any undelivered messages waiting.
     * Can be called by frontend to show "You have pending messages" indicator.
     */
    @Override
    public boolean hasUndeliveredMessages(String receiverId) {
        try {
            if (receiverId == null || receiverId.isEmpty()) {
                return false;
            }

            String redisKey = buildUndeliveredMessageKey(receiverId);
            Boolean hasKey = redisTemplate.hasKey(redisKey);

            boolean hasMessages = Boolean.TRUE.equals(hasKey);

            if (hasMessages) {
                log.debug("{} Receiver has undelivered messages - receiverId={}", LOG_PREFIX, receiverId);
            }

            return hasMessages;

        } catch (Exception e) {
            log.error("{} Failed to check undelivered messages for receiverId={}: {}",
                    LOG_PREFIX, receiverId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Build the Redis key for undelivered messages of a receiver.
     * Format: websocket:undelivered:{receiverId}
     */
    private String buildUndeliveredMessageKey(String receiverId) {
        return UNDELIVERED_MESSAGE_KEY_PREFIX + receiverId;
    }
}
