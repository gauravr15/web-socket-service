package com.odin.web_socket_service.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SessionRegistryService implements SessionManager { 

    // userId -> WebSocketSession
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    public void registerSession(String userId, WebSocketSession session) {
        if (userId == null || session == null) return;
        activeSessions.put(userId, session);
        log.info("Registered session for user: {}", userId);
    }

    public void unregisterSession(String userId) {
        if (userId == null) return;
        activeSessions.remove(userId);
        log.info("Unregistered session for user: {}", userId);
    }

    /**
     * Remove session by WebSocketSession instance and return userId if found.
     */
    public String removeSession(WebSocketSession session) {
        if (session == null) return null;
        String foundUserId = null;
        for (Map.Entry<String, WebSocketSession> e : activeSessions.entrySet()) {
            if (e.getValue().equals(session)) {
                foundUserId = e.getKey();
                break;
            }
        }
        if (foundUserId != null) {
            activeSessions.remove(foundUserId);
            log.info("Removed session for user: {}", foundUserId);
        }
        return foundUserId;
    }

    public WebSocketSession getSession(String userId) {
        return activeSessions.get(userId);
    }

    public boolean isUserOnline(String userId) {
        return activeSessions.containsKey(userId);
    }

    @Override
    public Map<String, WebSocketSession> getSessions() { 
        return activeSessions;
    }
    
    public String getUserIdBySession(WebSocketSession session) {
        for (var entry : activeSessions.entrySet()) {
            if (entry.getValue().equals(session)) {
                return entry.getKey();
            }
        }
        return null;
    }

}
