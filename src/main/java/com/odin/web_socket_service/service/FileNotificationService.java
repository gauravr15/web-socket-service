package com.odin.web_socket_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odin.web_socket_service.dto.FileMetadata;
import com.odin.web_socket_service.dto.Profile;
import com.odin.web_socket_service.dto.SendMessageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for notifying users about available file downloads via WebSocket.
 * 
 * Sends notifications to receivers when:
 * 1. New files are uploaded for them
 * 2. Files are ready for download
 * 
 * Notification uses the same SendMessageResponse structure as text messages
 * to maintain consistency with frontend parsing logic.
 */
@Slf4j
@Service
public class FileNotificationService {

    private final SessionRegistryService sessionRegistryService;
    private final ConnectionRegistryService connectionRegistryService;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;

    public FileNotificationService(
            SessionRegistryService sessionRegistryService,
            ConnectionRegistryService connectionRegistryService,
            MessageService messageService,
            ObjectMapper objectMapper) {
        this.sessionRegistryService = sessionRegistryService;
        this.connectionRegistryService = connectionRegistryService;
        this.messageService = messageService;
        this.objectMapper = objectMapper;
    }

    /**
     * Notify receiver about newly uploaded files via WebSocket.
     * 
     * This method:
     * 1. Checks if receiver is online on any pod
     * 2. If online, sends WebSocket notification
     * 3. If offline, does nothing (receiver will fetch files on next login)
     * 
     * @param fileMetadata Metadata of uploaded files
     */
    public void notifyReceiverAboutFiles(FileMetadata fileMetadata) {
        String receiverCustomerId = fileMetadata.getReceiverCustomerId();
        String senderCustomerId = fileMetadata.getSenderCustomerId();
        
        log.info("[FILE-NOTIFY] Processing notification - Sender: {}, Receiver: {}, Folder: {}", 
                senderCustomerId, receiverCustomerId, fileMetadata.getFolderName());
        
        try {
            // Check if receiver is connected to any pod
            boolean isReceiverOnline = connectionRegistryService.hasConnection(receiverCustomerId);
            
            if (!isReceiverOnline) {
                log.info("[FILE-NOTIFY] Receiver {} is offline. No WebSocket notification sent. " +
                        "Files will be available when receiver comes online.", receiverCustomerId);
                return;
            }
            
            // Get receiver's WebSocket session
            WebSocketSession receiverSession = sessionRegistryService.getSession(receiverCustomerId);
            
            if (receiverSession == null || !receiverSession.isOpen()) {
                log.warn("[FILE-NOTIFY] Receiver {} has stale connection. Session not found or closed.", 
                        receiverCustomerId);
                return;
            }
            
            // Build notification message using SendMessageResponse structure
            SendMessageResponse notification = buildFileNotification(fileMetadata);
            
            // Serialize to JSON
            String jsonMessage = objectMapper.writeValueAsString(notification);
            
            // Send WebSocket message
            receiverSession.sendMessage(new TextMessage(jsonMessage));
            
            log.info("[FILE-NOTIFY] Sent file notification to receiver {} via WebSocket. " +
                    "Folder: {}, Files: {}, Notification: {}", 
                    receiverCustomerId, 
                    fileMetadata.getFolderName(), 
                    fileMetadata.getFileCount(),
                    jsonMessage);
            
        } catch (Exception e) {
            log.error("[FILE-NOTIFY] Failed to send file notification to receiver {} via WebSocket", 
                    receiverCustomerId, e);
            // Don't throw - receiver can still fetch files via download API
        }
    }

    /**
     * Build file notification message using SendMessageResponse structure.
     * 
     * This maintains consistency with text message structure so frontend
     * can reuse the same parsing logic.
     * 
     * @param fileMetadata File metadata
     * @return SendMessageResponse notification
     */
    private SendMessageResponse buildFileNotification(FileMetadata fileMetadata) {
        // Create a simple map to indicate files are available
        // Actual files will be downloaded via HTTP API
        Map<String, String> filesNotification = new HashMap<>();
        filesNotification.put("notification", "FILES_AVAILABLE");
        filesNotification.put("folderName", fileMetadata.getFolderName());
        filesNotification.put("fileCount", String.valueOf(fileMetadata.getFileCount()));
        filesNotification.put("totalSize", String.valueOf(fileMetadata.getTotalSize()));
        
        // Retrieve sender profile to get mobile number (same as download API)
        String hashedSenderId = messageService.hashUserIdentifier(fileMetadata.getSenderCustomerId());
        Profile sender = messageService.getOrLoadProfile(hashedSenderId, fileMetadata.getSenderCustomerId());
        
        String senderMobile = null;
        if (sender != null) {
            senderMobile = sender.getMobile();
            log.debug("[FILE-NOTIFY] Retrieved sender profile - CustomerId: {}, Mobile: {}", 
                    fileMetadata.getSenderCustomerId(), senderMobile);
        } else {
            log.warn("[FILE-NOTIFY] Failed to retrieve sender profile for CustomerId: {}", 
                    fileMetadata.getSenderCustomerId());
        }
        
        return SendMessageResponse.builder()
                .senderId(fileMetadata.getSenderCustomerId())
                .senderMobile(senderMobile)
                .receiverId(fileMetadata.getReceiverCustomerId())
                .messageId(fileMetadata.getMessageId())
                .timestamp(fileMetadata.getCreationTimestamp())
                .messageType("FILE_UPLOAD_NOTIFICATION")
                .actualMessage(fileMetadata.getMessage())
                .files(filesNotification)
                .delivered(false) // Will be marked delivered after successful download
                .build();
    }

    /**
     * Notify receiver about multiple file batches available for download.
     * 
     * @param receiverCustomerId Receiver's customer ID
     * @param metadataList List of file metadata
     */
    public void notifyReceiverAboutMultipleFiles(String receiverCustomerId, List<FileMetadata> metadataList) {
        if (metadataList == null || metadataList.isEmpty()) {
            return;
        }
        
        for (FileMetadata metadata : metadataList) {
            notifyReceiverAboutFiles(metadata);
        }
        
        log.info("[FILE-NOTIFY] Sent {} file notifications to receiver {}", 
                metadataList.size(), receiverCustomerId);
    }
}
