package com.odin.web_socket_service.service;

import com.odin.web_socket_service.component.UndeliveredMessageComponent;
import com.odin.web_socket_service.dto.SendMessageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service layer for undelivered message management.
 * Delegates business logic to UndeliveredMessageComponent.
 * 
 * This service provides a clean interface for controllers and other services
 * to manage offline messages without direct Redis interaction.
 * 
 * Usage Pattern:
 * 1. Sender offline -> store message via storeUndeliveredMessage()
 * 2. Receiver comes online -> fetch via getUndeliveredMessages()
 * 3. Frontend displays messages -> delete via deleteUndeliveredMessages()
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UndeliveredMessageService implements IUndeliveredMessageService {

    private final UndeliveredMessageComponent undeliveredMessageComponent;
    private static final String LOG_PREFIX = "[UNDELIVERED-MESSAGE-SERVICE]";

    /**
     * Store an undelivered text message in Redis.
     * Called by MessageService when receiver is offline and message storage is enabled.
     * 
     * @param receiverId Customer ID of the receiver
     * @param message Complete SendMessageResponse object with sender info, content, timestamp
     */
    @Override
    public void storeUndeliveredMessage(String receiverId, SendMessageResponse message) {
        log.debug("{} Storing undelivered message for receiverId={}, messageId={}",
                LOG_PREFIX, receiverId, message != null ? message.getMessageId() : "null");
        undeliveredMessageComponent.storeUndeliveredMessage(receiverId, message);
    }

    /**
     * Retrieve all undelivered messages for a receiver from Redis.
     * 
     * Returns messages in SendMessageResponse format so frontend can process them
     * identically to real-time WebSocket messages.
     * 
     * This is typically called when:
     * 1. Receiver logs in
     * 2. Receiver connects to WebSocket
     * 3. Frontend polls for offline messages
     * 
     * @param receiverId Customer ID of the receiver
     * @return List of SendMessageResponse objects sorted by retrieval order
     */
    @Override
    public List<SendMessageResponse> getUndeliveredMessages(String receiverId) {
        log.info("{} Fetching undelivered messages for receiverId={}", LOG_PREFIX, receiverId);
        List<SendMessageResponse> messages = undeliveredMessageComponent.getUndeliveredMessages(receiverId);
        log.info("{} Retrieved {} undelivered message(s) for receiverId={}",
                LOG_PREFIX, messages.size(), receiverId);
        return messages;
    }

    /**
     * Delete all undelivered messages for a receiver after they have been delivered.
     * Called after frontend successfully receives and processes the messages via REST API.
     * 
     * This ensures one-time delivery semantics - messages won't be fetched again
     * on subsequent API calls.
     * 
     * @param receiverId Customer ID of the receiver
     */
    @Override
    public void deleteUndeliveredMessages(String receiverId) {
        log.debug("{} Deleting all undelivered messages for receiverId={}", LOG_PREFIX, receiverId);
        undeliveredMessageComponent.deleteUndeliveredMessages(receiverId);
    }

    /**
     * Delete a specific undelivered message by its messageId.
     * Allows selective deletion if receiver wants to delete individual messages.
     * 
     * @param receiverId Customer ID of the receiver
     * @param messageId ID of the specific message to delete
     */
    @Override
    public void deleteUndeliveredMessage(String receiverId, String messageId) {
        log.debug("{} Deleting specific undelivered message - receiverId={}, messageId={}",
                LOG_PREFIX, receiverId, messageId);
        undeliveredMessageComponent.deleteUndeliveredMessage(receiverId, messageId);
    }

    /**
     * Check if a receiver has any undelivered messages.
     * Can be used to display indicators or pre-fetch messages on login.
     * 
     * @param receiverId Customer ID of the receiver
     * @return true if undelivered messages exist, false otherwise
     */
    @Override
    public boolean hasUndeliveredMessages(String receiverId) {
        boolean hasMessages = undeliveredMessageComponent.hasUndeliveredMessages(receiverId);
        if (hasMessages) {
            log.debug("{} Receiver has undelivered messages - receiverId={}", LOG_PREFIX, receiverId);
        }
        return hasMessages;
    }
}
