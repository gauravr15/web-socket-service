package com.odin.web_socket_service.utility;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import com.odin.web_socket_service.service.ConnectionRegistryService;
import com.odin.web_socket_service.service.SessionManager;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class HeartbeatScheduler {
    
    private final ConnectionRegistryService registryService;
    private final SessionManager sessionManager;

    public HeartbeatScheduler(ConnectionRegistryService registryService, SessionManager sessionManager) {
        this.registryService = registryService;
        this.sessionManager = sessionManager;
    }

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void refreshActiveConnections() {
        Map<String, WebSocketSession> activeSessions = sessionManager.getSessions();
        log.debug("HeartbeatScheduler: refreshing TTL for {} active sessions", activeSessions.size());
        activeSessions.keySet().forEach(registryService::refreshConnectionTTL);
    }
}
