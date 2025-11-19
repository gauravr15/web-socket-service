package com.odin.web_socket_service.utility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odin.web_socket_service.service.MessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RedisMessageSubscriber implements MessageListener {

    private final MessageService messageService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisMessageSubscriber(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode json = mapper.readTree(message.getBody());
            String targetUserId = json.get("targetUserId").asText();
            String msg = json.get("message").asText();
            log.debug("Redis subscriber delivering message to {}", targetUserId);
            messageService.deliverRemoteMessage(targetUserId, msg);//
        } catch (Exception e) {
            log.error("Failed to process Redis message: {}", e.getMessage(), e);
        }
    }
}
