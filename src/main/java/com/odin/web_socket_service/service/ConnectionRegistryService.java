package com.odin.web_socket_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
public class ConnectionRegistryService {

    private final StringRedisTemplate redisTemplate;

    @Value("${pod.name:dev}")  // Default to "dev" if environment variable not set
    private String podName;

    public ConnectionRegistryService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void registerConnection(String userId) {
        try {
            String key = registryKey(userId);
            redisTemplate.opsForValue().set(key, podName, Duration.ofHours(1));
            log.info("Registered connection for userId='{}' on pod='{}' with TTL=1h", userId, podName);
        } catch (Exception e) {
            log.error("Failed to register connection for userId='{}': {}", userId, e.getMessage(), e);
        }
    }

    public void refreshConnectionTTL(String userId) {
        try {
            String key = registryKey(userId);
            Boolean exists = redisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(exists)) {
                redisTemplate.expire(key, Duration.ofHours(1));
                log.debug("Refreshed TTL for userId='{}'", userId);
            } else {
                log.debug("Skipping TTL refresh; no redis entry for userId={}", userId);
            }
        } catch (Exception e) {
            log.error("Failed to refresh TTL for userId='{}': {}", userId, e.getMessage(), e);
        }
    }

    public void unregisterConnection(String userId) {
        try {
            String key = registryKey(userId);
            redisTemplate.delete(key);
            log.info("Unregistered connection for userId='{}'", userId);
        } catch (Exception e) {
            log.error("Failed to unregister connection for userId='{}': {}", userId, e.getMessage(), e);
        }
    }

    /**
     * Returns the pod name if present in redis for the given user.
     * @return Optional.of(podName) if exists, otherwise Optional.empty()
     */
    public Optional<String> getConnectionPod(String userId) {
        try {
            String key = registryKey(userId);
            String pod = redisTemplate.opsForValue().get(key);
            if (pod != null && !pod.isEmpty()) {
                return Optional.of(pod);
            }
        } catch (Exception e) {
            log.error("Failed to get connection pod for userId='{}': {}", userId, e.getMessage(), e);
        }
        return Optional.empty();
    }

    public boolean hasConnection(String userId) {
        try {
            String key = registryKey(userId);
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("Failed to check connection existence for userId='{}': {}", userId, e.getMessage(), e);
            return false;
        }
    }

    private String registryKey(String userId) {
        return "websocket:connection:" + userId;
    }

    public String getPodName() {
        return podName;
    }
}
