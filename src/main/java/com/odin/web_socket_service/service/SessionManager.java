package com.odin.web_socket_service.service;

import java.util.Map;

import org.springframework.web.socket.WebSocketSession;

public interface SessionManager {
    Map<String, WebSocketSession> getSessions();
}