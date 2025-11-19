package com.odin.web_socket_service.utility;

import com.odin.web_socket_service.service.ConnectionRegistryService;
import com.odin.web_socket_service.service.MessageService;
import com.odin.web_socket_service.service.SessionRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

@Slf4j
@Component
public class CustomWebSocketHandler implements WebSocketHandler {

    private final JwtUtil jwtUtil;
    private final MessageService messageService;
    private final SessionRegistryService sessionRegistryService;
    private final ConnectionRegistryService connectionRegistryService;

    public CustomWebSocketHandler(JwtUtil jwtUtil,
                                  MessageService messageService,
                                  SessionRegistryService sessionRegistryService,
                                  ConnectionRegistryService connectionRegistryService) {
        this.jwtUtil = jwtUtil;
        this.messageService = messageService;
        this.sessionRegistryService = sessionRegistryService;
        this.connectionRegistryService = connectionRegistryService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = getQueryParam(session, "token");
        if (token == null || !jwtUtil.validateToken(token)) {
            log.warn("Invalid or missing token. Closing session.");
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        String userId = jwtUtil.getUserId(token);
        sessionRegistryService.registerSession(userId, session);
        connectionRegistryService.registerConnection(userId);

        log.info("User {} connected on pod {} with session {}",
                userId, connectionRegistryService.getPodName(), session.getId());
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
    	String payload = message.getPayload().toString();
        if (payload.contains("\"type\":\"ping\"")) {
            // Refresh TTL immediately in Redis for this user
            String userId = sessionRegistryService.getUserIdBySession(session);
            if (userId != null) {
                connectionRegistryService.refreshConnectionTTL(userId);
                log.debug("Received ping from {}, refreshed TTL", userId);
            }
            // Optionally, send pong response back
            session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            return; // Do not forward to MessageService
        }
        messageService.handleIncomingMessage(session, message);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Transport error for session {}: {}", session.getId(), exception.getMessage());
        session.close(CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        String disconnectedUserId = sessionRegistryService.removeSession(session);
        if (disconnectedUserId != null) {
            connectionRegistryService.unregisterConnection(disconnectedUserId);
            log.info("User {} disconnected and unregistered (session={}, status={})",
                    disconnectedUserId, session.getId(), closeStatus);
        } else {
            log.warn("Closed session {} not found in registry (status={})", session.getId(), closeStatus);
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    private String getQueryParam(WebSocketSession session, String param) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=");
                if (parts.length == 2 && parts[0].equals(param)) {
                    return parts[1];
                }
            }
        }
        return null;
    }
}
