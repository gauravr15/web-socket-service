package com.odin.web_socket_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import om.odin.web_socket_service.enums.NotificationChannel;

import java.util.Map;

/**
 * DTO for push notification messages sent to Kafka.
 * Matches the NotificationDTO format expected by the notification consumer.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationMessage {
    
    private Long customerId;  // Receiver ID as Long
    private Long notificationId;  // Fixed to 2001 for offline messages
    private NotificationChannel channel;  // SMS, EMAIL, INAPP
    private Map<String, String> map;  // Key: sampleMessage, Value: actual message
    private String mobile;  // Optional
    private String email;  // Optional
}
