package com.odin.web_socket_service.controller;

import com.odin.web_socket_service.dto.SendMessageRequest;
import com.odin.web_socket_service.dto.SendMessageResponse;
import com.odin.web_socket_service.dto.UserStatusResponse;
import com.odin.web_socket_service.service.ConnectionRegistryService;
import com.odin.web_socket_service.service.MessageService;
import com.odin.web_socket_service.utility.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/v1/websocket")
public class MessageController {

    private final MessageService messageService;
    private final ConnectionRegistryService connectionRegistryService;
    private final JwtUtil jwtUtil;

    public MessageController(MessageService messageService,
                             ConnectionRegistryService connectionRegistryService,
                             JwtUtil jwtUtil) {
        this.messageService = messageService;
        this.connectionRegistryService = connectionRegistryService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * GET /api/websocket/user-status/{userId}
     * Returns whether the user has an active websocket connection (online) and the pod name if available.
     */
    @GetMapping("/user-status/{userId}")
    public ResponseEntity<UserStatusResponse> userStatus(@PathVariable("userId") String userId) {
        log.debug("API /user-status called for userId={}", userId);
        boolean online = connectionRegistryService.hasConnection(userId);
        var podOpt = connectionRegistryService.getConnectionPod(userId);
        UserStatusResponse resp = new UserStatusResponse(online, podOpt.orElse(null));
        log.info("User status for {} => online={}, pod={}", userId, online, podOpt.orElse(null));
        return ResponseEntity.ok(resp);
    }

    /**
     * POST /api/websocket/send-message
     *
     * Accepts Authorization Bearer <token> header. Sender is determined from JWT sub.
     * Attempts immediate delivery; returns 200 + delivered=true if either delivered locally
     * or relayed to another pod. Returns 404 (or 409) if target offline or failed.
     */
    @PostMapping("/send-message")
    public ResponseEntity<Object> sendMessage(
            @RequestHeader HttpHeaders headers,
            @RequestBody SendMessageRequest request
    ) {
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("sendMessage: missing or invalid Authorization header");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization");
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        if (!jwtUtil.validateToken(token)) {
            log.warn("sendMessage: invalid token provided");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }

        String fromUserId = jwtUtil.getUserId(token);
        String targetUserId = request.getReceiverId();
        String message = request.getActualMessage();

        log.info("sendMessage called by {} → {}; messageLength={}", fromUserId, targetUserId,
                message != null ? message.length() : 0);

        // Check presence in Redis first
        boolean hasConnection = connectionRegistryService.hasConnection(targetUserId);
        if (!hasConnection) {
            log.info("sendMessage: target {} offline according to Redis", targetUserId);
            // Return 404 to caller to indicate offline — FE will fallback to message service offline flow
            return new ResponseEntity<>("User offline", HttpStatus.NOT_FOUND);
        }

        boolean success = messageService.deliverMessage(fromUserId, targetUserId, message);//
        if (success) {
            log.info("sendMessage: accepted for delivery {} → {}", fromUserId, targetUserId);
            return new ResponseEntity<>("Delivered or relayed", HttpStatus.OK);
        } else {
            log.warn("sendMessage: failed to deliver {} → {}", fromUserId, targetUserId);
            return new ResponseEntity("Delivery failed", HttpStatus.CONFLICT);
        }
    }
}
