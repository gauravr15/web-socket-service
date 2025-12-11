package com.odin.web_socket_service.controller;

import com.odin.web_socket_service.dto.SendMessageResponse;
import com.odin.web_socket_service.dto.UndeliveredMessagesResponse;
import com.odin.web_socket_service.service.IUndeliveredMessageService;
import com.odin.web_socket_service.utility.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for undelivered message management.
 * 
 * Provides endpoints for frontend to:
 * 1. Fetch undelivered text messages stored while receiver was offline
 * 2. Delete undelivered messages after processing
 * 3. Check if undelivered messages exist
 * 
 * Authentication: All endpoints require Authorization Bearer <JWT_TOKEN> header
 * UserId is extracted from JWT token for security
 * 
 * Flow:
 * 1. Sender sends text message while receiver is offline
 * 2. Backend stores message in Redis: websocket:undelivered:{receiverId}
 * 3. Backend sends push notification via Kafka (if enabled)
 * 4. Receiver logs in / comes online
 * 5. Frontend calls GET /v1/messages/undelivered
 * 6. Frontend receives list of undelivered messages
 * 7. Frontend displays messages to receiver (as if they came via WebSocket)
 * 8. Frontend calls DELETE /v1/messages/undelivered to mark as processed
 */
@Slf4j
@RestController
@RequestMapping("/v1/messages")
@RequiredArgsConstructor
public class UndeliveredMessageController {

    private final IUndeliveredMessageService undeliveredMessageService;
    private final JwtUtil jwtUtil;
    private static final String LOG_PREFIX = "[UNDELIVERED-MSG-CONTROLLER]";

    /**
     * GET /v1/messages/undelivered
     * 
     * Fetch all undelivered text messages for the authenticated user.
     * Messages are returned in SendMessageResponse format, identical to real-time WebSocket messages.
     * 
     * Frontend can process undelivered messages with the same handlers as live messages:
     * - Same DTO structure (senderId, senderMobile, actualMessage, timestamp, etc.)
     * - Can be displayed in same message list
     * - Contains complete sender information for UI
     * 
     * Authentication: Required (Bearer token)
     * User ID extracted from JWT token (sub claim)
     * 
     * @param headers HTTP headers containing Authorization: Bearer {token}
     * @return UndeliveredMessagesResponse with list of messages or empty list
     * @throws ResponseStatusException 401 if token invalid or missing
     *
     * Response Status Codes:
     * - 200 OK: Successfully retrieved messages (can be empty list)
     * - 401 UNAUTHORIZED: Invalid or missing token
     * - 500 INTERNAL_SERVER_ERROR: Server error retrieving messages
     *
     * Response Body Examples:
     * 
     * With messages:
     * {
     *   "messages": [
     *     {
     *       "senderId": "customer24",
     *       "senderMobile": "919905663451",
     *       "receiverId": "customer26",
     *       "messageId": "msg-uuid-1",
     *       "actualMessage": "Hello, are you there?",
     *       "timestamp": 1733654400000,
     *       "messageType": "chat",
     *       "delivered": false,
     *       "read": false,
     *       "senderName": "John Doe",
     *       "files": null
     *     },
     *     {
     *       "senderId": "customer25",
     *       "senderMobile": "919906554322",
     *       "receiverId": "customer26",
     *       "messageId": "msg-uuid-2",
     *       "actualMessage": "Check these files",
     *       "timestamp": 1733654500000,
     *       "messageType": "chat",
     *       "delivered": false,
     *       "read": false,
     *       "senderName": "Jane Smith",
     *       "files": {
     *         "document.pdf": "JVBERi0xLjQK...",
     *         "image.jpg": "iVBORw0KGgo..."
     *       }
     *     }
     *   ],
     *   "totalCount": 2,
     *   "hasMessages": true
     * }
     * 
     * Empty response:
     * {
     *   "messages": [],
     *   "totalCount": 0,
     *   "hasMessages": false
     * }
     */
    @GetMapping("/undelivered")
    public ResponseEntity<UndeliveredMessagesResponse> getUndeliveredMessages(
            @RequestHeader HttpHeaders headers
    ) {
        try {
            // Validate and extract token
            String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("{} Missing or invalid Authorization header", LOG_PREFIX);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header");
            }

            String token = authHeader.substring("Bearer ".length()).trim();
            if (!jwtUtil.validateToken(token)) {
                log.warn("{} Invalid JWT token provided", LOG_PREFIX);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
            }

            // Extract user ID from JWT
            String userId = jwtUtil.getUserId(token);
            log.info("{} Fetching undelivered messages for userId={}", LOG_PREFIX, userId);

            // Fetch all undelivered messages from Redis
            List<SendMessageResponse> messages = undeliveredMessageService.getUndeliveredMessages(userId);

            // Build response
            UndeliveredMessagesResponse response = UndeliveredMessagesResponse.builder()
                    .messages(messages)
                    .totalCount(messages.size())
                    .hasMessages(!messages.isEmpty())
                    .build();

            log.info("{} Retrieved {} undelivered message(s) for userId={}",
                    LOG_PREFIX, messages.size(), userId);

            // Auto-delete messages after retrieval (one-time delivery)
            if (!messages.isEmpty()) {
                undeliveredMessageService.deleteUndeliveredMessages(userId);
                log.info("{} Auto-deleted {} undelivered message(s) after retrieval for userId={}",
                        LOG_PREFIX, messages.size(), userId);
            }

            return ResponseEntity.ok(response);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} Unexpected error while fetching undelivered messages: {}",
                    LOG_PREFIX, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to retrieve undelivered messages");
        }
    }

    /**
     * DELETE /v1/messages/undelivered
     * 
     * Delete all undelivered messages for the authenticated user.
     * Called by frontend after processing undelivered messages.
     * 
     * Normally not needed since GET endpoint auto-deletes, but provided for explicit control.
     * 
     * Authentication: Required (Bearer token)
     * 
     * @param headers HTTP headers containing Authorization: Bearer {token}
     * @return Response with delete status
     * @throws ResponseStatusException 401 if token invalid or missing
     *
     * Response Body:
     * {
     *   "message": "Undelivered messages deleted successfully",
     *   "status": "success"
     * }
     */
    @DeleteMapping("/undelivered")
    public ResponseEntity<Map<String, String>> deleteUndeliveredMessages(
            @RequestHeader HttpHeaders headers
    ) {
        try {
            // Validate and extract token
            String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("{} Missing or invalid Authorization header for delete", LOG_PREFIX);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header");
            }

            String token = authHeader.substring("Bearer ".length()).trim();
            if (!jwtUtil.validateToken(token)) {
                log.warn("{} Invalid JWT token provided for delete", LOG_PREFIX);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
            }

            // Extract user ID from JWT
            String userId = jwtUtil.getUserId(token);
            log.info("{} Deleting all undelivered messages for userId={}", LOG_PREFIX, userId);

            // Delete all undelivered messages
            undeliveredMessageService.deleteUndeliveredMessages(userId);

            log.info("{} Successfully deleted all undelivered messages for userId={}", LOG_PREFIX, userId);

            return ResponseEntity.ok(Map.of(
                    "message", "Undelivered messages deleted successfully",
                    "status", "success"
            ));

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} Error deleting undelivered messages: {}",
                    LOG_PREFIX, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to delete undelivered messages");
        }
    }

    /**
     * GET /v1/messages/undelivered/check
     * 
     * Check if user has any undelivered messages without fetching them.
     * Useful for showing "You have pending messages" indicator in UI.
     * 
     * Authentication: Required (Bearer token)
     * 
     * @param headers HTTP headers containing Authorization: Bearer {token}
     * @return Response with availability status
     * @throws ResponseStatusException 401 if token invalid or missing
     *
     * Response Body:
     * {
     *   "hasMessages": true,
     *   "receiverId": "customer26"
     * }
     */
    @GetMapping("/undelivered/check")
    public ResponseEntity<Map<String, Object>> checkUndeliveredMessages(
            @RequestHeader HttpHeaders headers
    ) {
        try {
            // Validate and extract token
            String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("{} Missing or invalid Authorization header for check", LOG_PREFIX);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header");
            }

            String token = authHeader.substring("Bearer ".length()).trim();
            if (!jwtUtil.validateToken(token)) {
                log.warn("{} Invalid JWT token provided for check", LOG_PREFIX);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
            }

            // Extract user ID from JWT
            String userId = jwtUtil.getUserId(token);
            log.info("{} Checking for undelivered messages for userId={}", LOG_PREFIX, userId);

            // Check if messages exist
            boolean hasMessages = undeliveredMessageService.hasUndeliveredMessages(userId);

            return ResponseEntity.ok(Map.of(
                    "hasMessages", hasMessages,
                    "receiverId", userId
            ));

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} Error checking undelivered messages: {}",
                    LOG_PREFIX, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to check undelivered messages");
        }
    }
}
