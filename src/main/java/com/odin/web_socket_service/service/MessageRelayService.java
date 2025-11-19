package com.odin.web_socket_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageRelayService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CHANNEL = "websocket:messages";

    public void publish(String fromUserId, String targetUserId, String message) {
        try {
            var payload = objectMapper.createObjectNode()
                    .put("fromUserId", fromUserId)
                    .put("targetUserId", targetUserId)
                    .put("message", message);
            redisTemplate.convertAndSend(CHANNEL, payload.toString());
            log.debug("Published message from {} to {} via Redis", fromUserId, targetUserId);
        } catch (Exception e) {
            log.error("Failed to publish message via Redis: {}", e.getMessage(), e);
        }
    }

    public static ChannelTopic getTopic() {
        return new ChannelTopic(CHANNEL);
    }
}
