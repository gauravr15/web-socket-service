package com.odin.web_socket_service.utility;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.odin.web_socket_service.dto.NotificationDTO;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class NotificationUtility {

    private static final String TOPIC = "sample-message-topic";

    @Autowired
    private KafkaTemplate<String, NotificationDTO> notificationDTOKafkaTemplate;

    public void sendOtpMessage(NotificationDTO message) {
        log.info("Producing message to Kafka: {}", message);
        notificationDTOKafkaTemplate.send(TOPIC, message);
    }
}
