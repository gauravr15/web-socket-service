package com.odin.web_socket_service.utility;

import com.odin.web_socket_service.constants.ApplicationConstants;
import com.odin.web_socket_service.dto.NotificationDTO;
import com.odin.web_socket_service.dto.SendMessageResponse;
import lombok.extern.slf4j.Slf4j;
import com.odin.web_socket_service.enums.NotificationChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for handling notification publishing to Kafka.
 * Supports both OTP messages and undelivered message notifications.
 */
@Slf4j
@Component
public class NotificationUtility {

    private static final String OTP_TOPIC = "sample-message-topic";
    private static final String LOG_PREFIX = "[NOTIFICATION-UTILITY]";

    @Autowired
    private KafkaTemplate<String, NotificationDTO> notificationDTOKafkaTemplate;

    /**
     * Send OTP message to notification service via Kafka.
     * Legacy method for OTP notifications.
     * 
     * @param message NotificationDTO containing OTP details
     */
    public void sendOtpMessage(NotificationDTO message) {
        log.info("{} Producing OTP message to Kafka: {}", LOG_PREFIX, message);
        notificationDTOKafkaTemplate.send(OTP_TOPIC, message);
    }

    /**
     * Publish an undelivered message notification to Kafka.
     * Converts SendMessageResponse to NotificationDTO for notification service consumption.
     * 
     * Called when an offline message is stored in Redis to notify the notification service.
     * The notification service will consume this and handle further processing.
     * 
     * Flow:
     * 1. Text message cannot be delivered (receiver offline)
     * 2. Message stored in Redis for later retrieval
     * 3. This method publishes notification to Kafka
     * 4. Notification Service consumes and processes
     * 
     * @param receiverId The customer ID of the receiver
     * @param message The undelivered SendMessageResponse
     */
    public void publishUndeliveredMessageNotification(String receiverId, SendMessageResponse message) {
        try {
            // Validate inputs
            if (receiverId == null || receiverId.isEmpty()) {
                log.warn("{} Invalid receiverId provided", LOG_PREFIX);
                return;
            }

            if (message == null) {
                log.warn("{} Message object is null for receiverId={}", LOG_PREFIX, receiverId);
                return;
            }

            // Convert to NotificationDTO
            NotificationDTO notificationDTO = buildNotificationDTO(receiverId, message);

            // Build Kafka key for partitioning (ensures ordering per customer)
            String kafkaKey = buildKafkaKey(receiverId);

            // Publish to Kafka topic for notification service
            notificationDTOKafkaTemplate.send(
                    ApplicationConstants.KAFKA_UNDELIVERED_NOTIFICATION_TOPIC,
                    kafkaKey,
                    notificationDTO
            );

            log.info("{} Published undelivered message notification - receiverId={}, messageId={}, " +
                            "senderMobile={}, topic={}, key={}",
                    LOG_PREFIX, receiverId, message.getMessageId(),
                    message.getSenderMobile(),
                    ApplicationConstants.KAFKA_UNDELIVERED_NOTIFICATION_TOPIC,
                    kafkaKey);

        } catch (Exception e) {
            log.error("{} Failed to publish undelivered message notification for receiverId={}: {}",
                    LOG_PREFIX, receiverId, e.getMessage(), e);
        }
    }

    /**
     * Build NotificationDTO from SendMessageResponse.
     * Maps the message data to the notification format expected by notification service.
     * 
     * Notification map includes:
     * - senderMobile: Sender's mobile number
     * - senderCustomerId: Sender's customer ID
     * - message: Message content (actual text for chat, "Sent a file" for non-text)
     * 
     * For text messages (messageType="chat"), includes the actual message content.
     * For non-text messages (files, calls, etc.), uses generic "Sent a file" message.
     * 
     * @param receiverId The customer ID of the receiver
     * @param message The undelivered message
     * @return NotificationDTO with populated fields including senderCustomerId
     */
    private NotificationDTO buildNotificationDTO(String receiverId, SendMessageResponse message) {
        // Build the notification map with required fields
        Map<String, String> notificationMap = new HashMap<>();
        
        // Add sender mobile (available from message)
        if (message.getSenderMobile() != null && !message.getSenderMobile().isEmpty()) {
            notificationMap.put(ApplicationConstants.NOTIFICATION_MAP_SENDER_MOBILE, 
                    message.getSenderMobile());
        }
        
        // Add sender customer ID
        if (message.getSenderId() != null && !message.getSenderId().isEmpty()) {
            notificationMap.put(ApplicationConstants.NOTIFICATION_MAP_SENDER_CUSTOMER_ID, 
                    message.getSenderId());
        }
        
        // Determine message value based on message type
        String messageValue = ApplicationConstants.GENERIC_FILE_MESSAGE; // Default for non-text
        
        // For text messages, use actual message content
        if (isTextMessage(message)) {
            if (message.getActualMessage() != null && !message.getActualMessage().isEmpty()) {
                messageValue = message.getActualMessage();
            }
        }
        
        // Add message content to notification map
        notificationMap.put(ApplicationConstants.NOTIFICATION_MAP_MESSAGE, messageValue);

        // Build and return NotificationDTO
        return NotificationDTO.builder()
                .customerId(convertToLong(receiverId))
                .notificationId(ApplicationConstants.DEFAULT_NOTIFICATION_ID)
                .channel(NotificationChannel.INAPP)
                .map(notificationMap)
                .mobile(null) // Receiver mobile not available in SendMessageResponse
                .email(null) // Email not provided in offline message context
                .build();
    }

    /**
     * Check if a message is a text message based on message type.
     * Text messages have messageType="chat".
     * Non-text messages are calls, file transfers, ICE candidates, etc.
     * 
     * @param message The SendMessageResponse to check
     * @return true if message is a text message, false otherwise
     */
    private boolean isTextMessage(SendMessageResponse message) {
        if (message == null) {
            return false;
        }
        
        String messageType = message.getMessageType();
        if (messageType == null || messageType.isEmpty()) {
            // If message type is not specified, treat as text if actualMessage exists
            return message.getActualMessage() != null && !message.getActualMessage().isEmpty();
        }
        
        return ApplicationConstants.MESSAGE_TYPE_CHAT.equalsIgnoreCase(messageType);
    }

    /**
     * Build Kafka message key for partitioning and ordering.
     * Key is based on receiverId to ensure all messages for a customer go to same partition.
     * This maintains message ordering per customer.
     * 
     * @param receiverId The customer ID
     * @return Kafka message key in format: "undelivered:{receiverId}"
     */
    private String buildKafkaKey(String receiverId) {
        return ApplicationConstants.KAFKA_UNDELIVERED_MESSAGE_KEY_PREFIX + receiverId;
    }

    /**
     * Convert String receiverId to Long customerId.
     * 
     * @param receiverId The receiver ID as string
     * @return The receiver ID as Long, or null if conversion fails
     */
    private Long convertToLong(String receiverId) {
        try {
            return Long.parseLong(receiverId);
        } catch (NumberFormatException e) {
            log.warn("{} Failed to convert receiverId to Long: {}", LOG_PREFIX, receiverId);
            return null;
        }
    }
}

