package com.odin.web_socket_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
public class ConnectionRegistryService {

    private final StringRedisTemplate redisTemplate;

    @Value("${pod.name:dev}")  // Default to "dev" if environment variable not set
    private String podName;

    @Value("${connection.registry.ttl.seconds:30}")  // Default to 30 seconds (for informational logging only)
    private long connectionTtlSeconds;

    public ConnectionRegistryService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Register a connection for the given user.
     * Entry persists until explicit unregisterConnection() call (on WebSocket disconnect).
     * No auto-expiry - relies on explicit disconnect to clean up.
     */
    public void registerConnection(String userId) {
        try {
            String key = registryKey(userId);
            // Store pod name without TTL - entry persists until explicit disconnect
            redisTemplate.opsForValue().set(key, podName);
            log.info("Registered connection for userId='{}' on pod='{}' (persists until disconnect)", 
                    userId, podName);
        } catch (Exception e) {
            log.error("Failed to register connection for userId='{}': {}", userId, e.getMessage(), e);
        }
    }

    /**
     * Refresh the connection TTL (kept for backward compatibility with ping-pong).
     * With Option 3 strategy, this is a no-op since entry has no expiry.
     * Pings can still be used for application-level heartbeat/latency checks.
     */
    public void refreshConnectionTTL(String userId) {
        try {
            String key = registryKey(userId);
            Boolean exists = redisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(exists)) {
                log.debug("Ping received for userId='{}' (connection entry persists until disconnect)", userId);
            } else {
                log.debug("Ping received but no redis entry for userId={} - user may have disconnected", userId);
            }
        } catch (Exception e) {
            log.error("Failed to refresh connection for userId='{}': {}", userId, e.getMessage(), e);
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
