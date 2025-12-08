package com.odin.web_socket_service.service;

import com.odin.web_socket_service.dto.NotificationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import om.odin.web_socket_service.enums.NotificationChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for producing notification messages to Kafka.
 * Sends push notification events when receiver is offline.
 * Publishes in the format expected by the notification consumer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaNotificationService {

    private final KafkaTemplate<String, NotificationMessage> kafkaTemplate;

    @Value("${kafka.notification.topic:notification-events}")
    private String notificationTopic;

    @Value("${offline.notification.channel:SMS}")
    private String notificationChannel;

    @Value("${offline.notification.enabled:true}")
    private boolean notificationEnabled;

    private static final Long OFFLINE_MESSAGE_NOTIFICATION_ID = 2001L;

    /**
     * Publish a notification message to Kafka.
     * This is called when a message receiver is offline.
     * Creates a NotificationDTO in the format expected by the notification consumer.
     */
    public void publishNotification(String receiverId, String senderId, String sampleMessage, 
                                   String messageId, Long timestamp) {
        try {
            if (!notificationEnabled) {
                log.debug("Notification publishing is disabled via configuration");
                return;
            }

            if (receiverId == null || receiverId.isEmpty() || sampleMessage == null) {
                log.warn("publishNotification: Invalid inputs - receiverId={}, sampleMessage={}", 
                        receiverId, sampleMessage);
                return;
            }

            // Convert receiverId to Long (customer ID)
            Long customerId;
            try {
                customerId = Long.parseLong(receiverId);
            } catch (NumberFormatException e) {
                log.warn("publishNotification: Invalid receiverId format (not a number): {}", receiverId);
                customerId = 0L; // Fallback
            }

            // Create map with sampleMessage as key and value
            Map<String, String> notificationMap = new HashMap<>();
            notificationMap.put("sampleMessage", sampleMessage);
            notificationMap.put("messageId", messageId != null ? messageId : UUID.randomUUID().toString());
            if (senderId != null) {
                notificationMap.put("senderId", senderId);
            }

            // Create NotificationMessage with proper format
            NotificationMessage notification = NotificationMessage.builder()
                    .customerId(customerId)
                    .notificationId(OFFLINE_MESSAGE_NOTIFICATION_ID)
                    .channel(NotificationChannel.valueOf(notificationChannel))
                    .map(notificationMap)
                    .build();

            // Send to Kafka topic with customerId as partition key
            kafkaTemplate.send(notificationTopic, String.valueOf(customerId), notification);
            
            log.info("Published notification to Kafka - topic={}, customerId={}, channel={}, messageId={}", 
                    notificationTopic, customerId, notificationChannel, messageId);
        } catch (Exception e) {
            log.error("Failed to publish notification to Kafka for receiver={}: {}", 
                    receiverId, e.getMessage(), e);
        }
    }

    /**
     * Publish notification with minimal payload (typically sample message).
     */
    public void publishNotificationSimple(String receiverId, String senderId, String sampleMessage) {
        publishNotification(receiverId, senderId, sampleMessage, null, null);
    }
}
