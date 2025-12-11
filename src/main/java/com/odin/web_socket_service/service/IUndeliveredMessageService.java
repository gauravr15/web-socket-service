package com.odin.web_socket_service.service;

import com.odin.web_socket_service.dto.SendMessageResponse;
import java.util.List;

/**
 * Service interface for managing undelivered text messages stored in Redis.
 * Provides operations for storing, retrieving, and deleting offline messages.
 */
public interface IUndeliveredMessageService {

    /**
     * Store an undelivered text message in Redis.
     * Messages are stored with configurable TTL (default: 30 days).
     * 
     * @param receiverId Customer ID of the receiver
     * @param message Complete SendMessageResponse object to store
     */
    void storeUndeliveredMessage(String receiverId, SendMessageResponse message);

    /**
     * Retrieve all undelivered messages for a receiver.
     * 
     * @param receiverId Customer ID of the receiver
     * @return List of SendMessageResponse objects, empty list if no messages found
     */
    List<SendMessageResponse> getUndeliveredMessages(String receiverId);

    /**
     * Delete all undelivered messages for a receiver after they have been delivered.
     * Called after frontend receives the messages via the REST API.
     * 
     * @param receiverId Customer ID of the receiver
     */
    void deleteUndeliveredMessages(String receiverId);

    /**
     * Delete a specific undelivered message by its messageId.
     * 
     * @param receiverId Customer ID of the receiver
     * @param messageId ID of the specific message to delete
     */
    void deleteUndeliveredMessage(String receiverId, String messageId);

    /**
     * Check if a receiver has any undelivered messages.
     * 
     * @param receiverId Customer ID of the receiver
     * @return true if undelivered messages exist, false otherwise
     */
    boolean hasUndeliveredMessages(String receiverId);
}
