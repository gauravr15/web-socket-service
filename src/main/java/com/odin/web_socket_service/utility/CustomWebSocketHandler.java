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
        log.info("[DEBUG-CONNECT] afterConnectionEstablished() called - sessionId={}, timestamp={}",
                session.getId(), System.currentTimeMillis());
        
        String token = getQueryParam(session, "token");
        if (token == null || !jwtUtil.validateToken(token)) {
            log.warn("[DEBUG-CONNECT] Invalid or missing token. Closing session: {}", session.getId());
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        String userId = jwtUtil.getUserId(token);
        log.info("[DEBUG-CONNECT] Extracted userId={} from token", userId);
        
        sessionRegistryService.registerSession(userId, session);
        connectionRegistryService.registerConnection(userId);

        log.info("[DEBUG-CONNECT] User {} connected on pod {} with session {} - Ready to receive messages",
                userId, connectionRegistryService.getPodName(), session.getId());
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        long entryTime = System.currentTimeMillis();
        String userId = "UNKNOWN";
        
        try {
            userId = sessionRegistryService.getUserIdBySession(session);
        } catch (Exception e) {
            log.error("[🔥 CRITICAL-ERROR] Failed to get userId from session: {}", e.getMessage(), e);
        }
        
        // DEBUG ENTRY POINT 1: Log message reception with timestamp
        log.error("[🔥 CRITICAL-ENTRY] ============================================");
        log.error("[🔥 CRITICAL-ENTRY] handleMessage() CALLED - MESSAGE RECEIVED!");
        log.error("[🔥 CRITICAL-ENTRY] ============================================");
        log.info("[DEBUG-ENTRY-1] handleMessage() called - userId={}, sessionId={}, sessionOpen={}, timestamp={}",
                userId, session.getId(), session.isOpen(), entryTime);
        log.error("[🔥 CRITICAL-ENTRY] Message class: {}", message.getClass().getName());
        log.error("[🔥 CRITICAL-ENTRY] Payload class: {}", message.getPayload() != null ? message.getPayload().getClass().getName() : "NULL");
        
        try {
            String payload = message.getPayload().toString();
            int payloadLength = payload.length();
            
            // DEBUG: Log payload details with first 200 chars for inspection
            log.info("[DEBUG-ENTRY-2] Payload received - userId={}, size={} bytes ({} MB), messageType={}, timestamp={}",
                    userId, payloadLength, String.format("%.2f", payloadLength / 1024.0 / 1024.0), 
                    message.getClass().getSimpleName(), System.currentTimeMillis());
            log.info("[DEBUG-ENTRY-2-PREVIEW] First 200 chars of payload: {}",
                    payload.length() > 200 ? payload.substring(0, 200) + "..." : payload);
            
            // Check if payload contains files
            boolean containsFiles = payload.contains("\"files\"");
            if (containsFiles) {
                log.info("[DEBUG-FILES] Message contains files field - userId={}, timestamp={}", userId, System.currentTimeMillis());
            }
            
            if (payload.contains("\"type\":\"ping\"")) {
                // Handle ping for application-level heartbeat/latency checks
                if (userId != null) {
                    log.debug("Received heartbeat ping from userId={}", userId);
                }
                // Send pong response back
                session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
                return; // Do not forward to MessageService
            }
            
            // DEBUG: Before calling MessageService
            log.info("[DEBUG-ENTRY-3] Forwarding to MessageService.handleIncomingMessage() - userId={}", userId);
            
            messageService.handleIncomingMessage(session, message);
            
            // DEBUG: After MessageService returns successfully
            long completionTime = System.currentTimeMillis();
            long processingDuration = completionTime - entryTime;
            log.info("[DEBUG-ENTRY-4] MessageService.handleIncomingMessage() completed successfully - userId={}, duration={}ms, timestamp={}", 
                    userId, processingDuration, completionTime);
            
        } catch (Exception e) {
            // DEBUG: Catch any exception
            log.error("[🔥 CRITICAL-ERROR] ============================================");
            log.error("[🔥 CRITICAL-ERROR] EXCEPTION CAUGHT IN handleMessage()!");
            log.error("[🔥 CRITICAL-ERROR] ============================================");
            log.error("[DEBUG-ERROR] Exception in handleMessage() - userId={}, sessionId={}, error={}", 
                    userId, session.getId(), e.getClass().getName(), e);
            log.error("[DEBUG-ERROR] Error message: {}", e.getMessage());
            log.error("[DEBUG-ERROR] Full stack trace:", e);
            
            // Check if the exception is related to payload size or parsing
            if (e.getMessage() != null) {
                if (e.getMessage().contains("too large") || e.getMessage().contains("too big") || 
                    e.getMessage().contains("buffer") || e.getMessage().contains("size")) {
                    log.error("[🔥 CRITICAL-ERROR] 🔴 SIZE-RELATED ERROR DETECTED!");
                    log.error("[🔥 CRITICAL-ERROR] This might be why the message isn't processed!");
                }
            }
            
            log.error("[🔥 CRITICAL-ERROR] Connection will NOT be closed - keeping alive");
            // Don't close the connection, just log the error
            // This prevents disconnection on transient errors
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String userId = sessionRegistryService.getUserIdBySession(session);
        
        // DEBUG: Log transport error details
        log.error("[🔥 CRITICAL-TRANSPORT-ERROR] ============================================");
        log.error("[🔥 CRITICAL-TRANSPORT-ERROR] TRANSPORT ERROR - THIS MIGHT BE WHY NO MESSAGE!");
        log.error("[🔥 CRITICAL-TRANSPORT-ERROR] ============================================");
        log.error("[DEBUG-TRANSPORT-ERROR] Transport error occurred:");
        log.error("  - UserId: {}", userId);
        log.error("  - SessionId: {}", session.getId());
        log.error("  - Exception Type: {}", exception.getClass().getName());
        log.error("  - Exception Message: {}", exception.getMessage());
        log.error("  - Full stack trace:", exception);
        
        log.error("Transport error for session {} (userId={}): {}", 
                session.getId(), userId, exception.getMessage(), exception);
        session.close(CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        long disconnectTime = System.currentTimeMillis();
        String disconnectedUserId = sessionRegistryService.removeSession(session);
        
        // Determine who initiated the close and what it means
        String initiator = "UNKNOWN";
        String interpretation = "Unknown close reason";
        boolean isFrontendClaim = false;
        
        switch (closeStatus.getCode()) {
            case 1000:
                initiator = "CLIENT";
                interpretation = "Normal closure - client closed connection cleanly";
                break;
            case 1001:
                initiator = "CLIENT";
                interpretation = "Client going away (page refresh/navigation/app backgrounded)";
                break;
            case 1002:
                initiator = "EITHER";
                interpretation = "Protocol error - malformed frame detected";
                break;
            case 1003:
                initiator = "EITHER";
                interpretation = "Unsupported data type received";
                break;
            case 1007:
                initiator = "SERVER";
                interpretation = "Invalid frame payload data (not consistent with message type)";
                break;
            case 1008:
                initiator = "SERVER";
                interpretation = "Policy violation - message rejected";
                break;
            case 1009:
                initiator = "SERVER";
                interpretation = "🔴 MESSAGE TOO LARGE - PAYLOAD EXCEEDS CONFIGURED BUFFER SIZE!";
                isFrontendClaim = true;
                break;
            case 1010:
                initiator = "CLIENT";
                interpretation = "Client expected extension server didn't provide";
                break;
            case 1011:
                initiator = "SERVER";
                interpretation = "Server encountered unexpected condition";
                break;
            case 1012:
                initiator = "SERVER";
                interpretation = "Service restart";
                break;
            case 1013:
                initiator = "SERVER";
                interpretation = "Try again later";
                break;
            case 1015:
                initiator = "EITHER";
                interpretation = "TLS handshake failure";
                break;
            default:
                if (closeStatus.getCode() >= 1000 && closeStatus.getCode() <= 1015) {
                    initiator = "STANDARD";
                } else if (closeStatus.getCode() >= 3000 && closeStatus.getCode() <= 3999) {
                    initiator = "LIBRARY";
                } else if (closeStatus.getCode() >= 4000 && closeStatus.getCode() <= 4999) {
                    initiator = "APPLICATION";
                }
                interpretation = "Non-standard close code";
        }
        
        // DEBUG: Log detailed disconnection information with timing
        log.error("[🔥 CRITICAL-DISCONNECT] ═══════════════════════════════════════════════");
        log.error("[🔥 CRITICAL-DISCONNECT] CONNECTION CLOSED - User: {}", disconnectedUserId);
        log.error("[🔥 CRITICAL-DISCONNECT] ═══════════════════════════════════════════════");
        log.error("[🔥 CRITICAL-DISCONNECT] Session ID: {}", session.getId());
        log.error("[🔥 CRITICAL-DISCONNECT] Timestamp: {}", disconnectTime);
        log.error("[🔥 CRITICAL-DISCONNECT] ");
        log.error("[🔥 CRITICAL-DISCONNECT] 🚨 CLOSE CODE: {} ({})", closeStatus.getCode(), getCloseStatusName(closeStatus.getCode()));
        log.error("[🔥 CRITICAL-DISCONNECT] 📋 Initiated by: {}", initiator);
        log.error("[🔥 CRITICAL-DISCONNECT] 📝 Interpretation: {}", interpretation);
        log.error("[🔥 CRITICAL-DISCONNECT] 💬 Close Reason: {}", closeStatus.getReason() != null ? closeStatus.getReason() : "null");
        log.error("[🔥 CRITICAL-DISCONNECT] ");
        
        // Special handling for code 1009 (TOO_LARGE)
        if (closeStatus.getCode() == 1009) {
            log.error("[🔥 CRITICAL-DISCONNECT] ╔═══════════════════════════════════════════════════╗");
            log.error("[🔥 CRITICAL-DISCONNECT] ║  🔴 CLOSE CODE 1009: MESSAGE TOO LARGE CONFIRMED ║");
            log.error("[🔥 CRITICAL-DISCONNECT] ╚═══════════════════════════════════════════════════╝");
            log.error("[🔥 CRITICAL-DISCONNECT] ");
            log.error("[🔥 CRITICAL-DISCONNECT] 📊 FRONTEND CLAIM VALIDATION:");
            log.error("[🔥 CRITICAL-DISCONNECT]    ✅ Frontend was correct - payload rejected as too big");
            log.error("[🔥 CRITICAL-DISCONNECT]    ✅ Close reason: {}", closeStatus.getReason());
            log.error("[🔥 CRITICAL-DISCONNECT]    ❌ Backend configuration issue - buffer size NOT applied");
            log.error("[🔥 CRITICAL-DISCONNECT] ");
            log.error("[🔥 CRITICAL-DISCONNECT] 🔍 DIAGNOSIS:");
            log.error("[🔥 CRITICAL-DISCONNECT]    1. Check WebSocketRuntimeValidator logs at startup");
            log.error("[🔥 CRITICAL-DISCONNECT]    2. Verify actual buffer size from ServerContainer");
            log.error("[🔥 CRITICAL-DISCONNECT]    3. If < 64KB: ServletServerContainerFactoryBean FAILED");
            log.error("[🔥 CRITICAL-DISCONNECT]    4. Tomcat's default 8KB is likely active");
            log.error("[🔥 CRITICAL-DISCONNECT] ");
            log.error("[🔥 CRITICAL-DISCONNECT] 🔧 REQUIRED ACTION:");
            log.error("[🔥 CRITICAL-DISCONNECT]    → Fix WebSocketConfig.java @Bean configuration");
            log.error("[🔥 CRITICAL-DISCONNECT]    → Add application.properties: server.servlet.context-parameters.*");
            log.error("[🔥 CRITICAL-DISCONNECT]    → Or use @ServerEndpoint with DeploymentConfig");
        }
        
        log.error("[🔥 CRITICAL-DISCONNECT] ");
        log.error("[🔥 CRITICAL-DISCONNECT] 🔍 MESSAGE RECEPTION CHECK:");
        log.error("[🔥 CRITICAL-DISCONNECT] Look for [🔥 CRITICAL-ENTRY] log BEFORE this disconnect.");
        log.error("[🔥 CRITICAL-DISCONNECT] ");
        
        if (isFrontendClaim) {
            log.error("[🔥 CRITICAL-DISCONNECT] ❌ NO [CRITICAL-ENTRY] expected - message rejected at lower layer");
            log.error("[🔥 CRITICAL-DISCONNECT] ❌ Tomcat rejected before reaching handler");
        } else {
            log.error("[🔥 CRITICAL-DISCONNECT] If NO [CRITICAL-ENTRY]: Frontend never sent data");
            log.error("[🔥 CRITICAL-DISCONNECT] If YES [CRITICAL-ENTRY]: Message processed, then disconnect");
        }
        
        log.error("[🔥 CRITICAL-DISCONNECT] ═══════════════════════════════════════════════");
        log.error("[DEBUG-DISCONNECT] Stack trace at disconnect:", new Exception("Disconnect trace"));
        
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
    
    private String getCloseStatusName(int code) {
        switch (code) {
            case 1000: return "NORMAL_CLOSURE";
            case 1001: return "GOING_AWAY";
            case 1002: return "PROTOCOL_ERROR";
            case 1003: return "UNSUPPORTED_DATA";
            case 1005: return "NO_STATUS_RECEIVED";
            case 1006: return "ABNORMAL_CLOSURE";
            case 1007: return "INVALID_PAYLOAD_DATA";
            case 1008: return "POLICY_VIOLATION";
            case 1009: return "MESSAGE_TOO_BIG";
            case 1010: return "MANDATORY_EXTENSION";
            case 1011: return "INTERNAL_SERVER_ERROR";
            case 1015: return "TLS_HANDSHAKE_FAILURE";
            default: return "UNKNOWN_CODE_" + code;
        }
    }
}
