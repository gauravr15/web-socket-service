package com.odin.web_socket_service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Low-level WebSocket handshake interceptor to log connection attempts
 * and verify that the WebSocket upgrade is happening correctly.
 */
@Slf4j
public class WebSocketLoggingInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        log.error("🔵 [INTERCEPTOR-BEFORE-HANDSHAKE] ============================================");
        log.error("🔵 [INTERCEPTOR-BEFORE-HANDSHAKE] WebSocket upgrade request received");
        log.error("🔵 [INTERCEPTOR-BEFORE-HANDSHAKE] URI: {}", request.getURI());
        log.error("🔵 [INTERCEPTOR-BEFORE-HANDSHAKE] Method: {}", request.getMethod());
        log.error("🔵 [INTERCEPTOR-BEFORE-HANDSHAKE] Headers: {}", request.getHeaders());
        log.error("🔵 [INTERCEPTOR-BEFORE-HANDSHAKE] ============================================");
        return true; // Allow handshake to proceed
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("🔴 [INTERCEPTOR-AFTER-HANDSHAKE] ============================================");
            log.error("🔴 [INTERCEPTOR-AFTER-HANDSHAKE] WebSocket handshake FAILED!");
            log.error("🔴 [INTERCEPTOR-AFTER-HANDSHAKE] Exception: {}", exception.getMessage(), exception);
            log.error("🔴 [INTERCEPTOR-AFTER-HANDSHAKE] ============================================");
        } else {
            log.error("🟢 [INTERCEPTOR-AFTER-HANDSHAKE] ============================================");
            log.error("🟢 [INTERCEPTOR-AFTER-HANDSHAKE] WebSocket handshake successful");
            log.error("🟢 [INTERCEPTOR-AFTER-HANDSHAKE] Connection upgraded to WebSocket protocol");
            log.error("🟢 [INTERCEPTOR-AFTER-HANDSHAKE] ============================================");
        }
    }
}
