package com.odin.web_socket_service.component;

import com.odin.web_socket_service.config.FileStorageProperties;
import com.odin.web_socket_service.dto.FileMetadata;
import com.odin.web_socket_service.dto.Profile;
import com.odin.web_socket_service.dto.SendMessageResponse;
import com.odin.web_socket_service.service.FileMetadataService;
import com.odin.web_socket_service.service.FileStorageService;
import com.odin.web_socket_service.service.IFileDownloadService;
import com.odin.web_socket_service.service.MessageService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Component for handling file download business logic.
 * 
 * Responsibilities:
 * - Retrieve file metadata from Redis
 * - Read and encode files from file system
 * - Build response objects
 * - Manage cleanup after delivery
 */
@Slf4j
@Component
public class FileDownloadComponent implements IFileDownloadService {

    private final FileStorageService fileStorageService;
    private final FileMetadataService fileMetadataService;
    private final FileStorageProperties fileStorageProperties;
    private final MessageService messageService;

    public FileDownloadComponent(
            FileStorageService fileStorageService,
            FileMetadataService fileMetadataService,
            FileStorageProperties fileStorageProperties,
            MessageService messageService) {
        this.fileStorageService = fileStorageService;
        this.fileMetadataService = fileMetadataService;
        this.fileStorageProperties = fileStorageProperties;
        this.messageService = messageService;
    }

    @Override
    public List<SendMessageResponse> downloadFiles(String receiverCustomerId) {
        long startTime = System.currentTimeMillis();
        
        log.info("[FILE-DOWNLOAD-COMPONENT] Processing download for receiver: {}", receiverCustomerId);
        
        // Check if feature is enabled
        if (!fileStorageProperties.isEnabled()) {
            throw new IllegalStateException("File download service is currently unavailable");
        }
        
        // Retrieve all file metadata for receiver from Redis
        List<FileMetadata> metadataList = fileMetadataService.getFileMetadataList(receiverCustomerId);
        
        if (metadataList.isEmpty()) {
            log.info("[FILE-DOWNLOAD-COMPONENT] No files available for receiver: {}", receiverCustomerId);
            return new ArrayList<>();
        }
        
        log.info("[FILE-DOWNLOAD-COMPONENT] Found {} file batches for receiver", metadataList.size());
        
        // Build response list
        List<SendMessageResponse> responseList = new ArrayList<>();
        List<String> successfullyDeliveredFolders = new ArrayList<>();
        
        for (FileMetadata metadata : metadataList) {
            try {
                // Read files from file system and encode as base64
                Map<String, String> filesMap;
                try {
                    filesMap = fileStorageService.retrieveFilesAsBase64(metadata.getFolderName());
                } catch (java.io.IOException e) {
                    log.error("[FILE-DOWNLOAD-COMPONENT] Failed to read files from folder: {}", 
                            metadata.getFolderName(), e);
                    continue;
                }
                
                if (filesMap.isEmpty()) {
                    log.warn("[FILE-DOWNLOAD-COMPONENT] No files found in folder: {}", metadata.getFolderName());
                    continue;
                }
                String hashedSenderId = messageService.hashUserIdentifier(metadata.getSenderCustomerId());
        		Profile sender = messageService.getOrLoadProfile(hashedSenderId, metadata.getSenderCustomerId());
                
                // Build SendMessageResponse with files
                SendMessageResponse response = SendMessageResponse.builder()
                        .senderId(metadata.getSenderCustomerId())
                        .senderMobile(sender.getMobile())
                        .receiverId(metadata.getReceiverCustomerId())
                        .messageId(metadata.getMessageId())
                        .timestamp(metadata.getCreationTimestamp())
                        .messageType(metadata.getMessageType())
                        .actualMessage(metadata.getMessage())
                        .files(filesMap)
                        .delivered(true)
                        .deliveryTimestamp(System.currentTimeMillis())
                        .isRead(false)
                        .build();
                
                responseList.add(response);
                successfullyDeliveredFolders.add(metadata.getFolderName());
                
                log.info("[FILE-DOWNLOAD-COMPONENT] Prepared {} files from folder: {} (sender: {})", 
                        filesMap.size(), metadata.getFolderName(), metadata.getSenderCustomerId());
                
            } catch (Exception e) {
                log.error("[FILE-DOWNLOAD-COMPONENT] Failed to read files from folder: {}", 
                        metadata.getFolderName(), e);
                // Continue with other folders
            }
        }
        
        // Sort by timestamp (ascending) - oldest first
        responseList.sort((r1, r2) -> Long.compare(r1.getTimestamp(), r2.getTimestamp()));
        
        // Clean up after successful delivery
        cleanupDeliveredFiles(receiverCustomerId, successfullyDeliveredFolders);
        
        long duration = System.currentTimeMillis() - startTime;
        int totalFilesDelivered = responseList.stream().mapToInt(r -> r.getFiles().size()).sum();
        
        log.info("[FILE-DOWNLOAD-COMPONENT] ✅ Download completed in {}ms - Batches: {}, Total files: {}", 
                duration, responseList.size(), totalFilesDelivered);
        
        return responseList;
    }

    @Override
    public boolean hasAvailableFiles(String receiverCustomerId) {
        try {
            return fileMetadataService.hasAvailableFiles(receiverCustomerId);
        } catch (Exception e) {
            log.error("[FILE-DOWNLOAD-COMPONENT] Failed to check file availability for receiver: {}", 
                    receiverCustomerId, e);
            return false;
        }
    }

    @Override
    public int getTotalPendingFilesCount(String receiverCustomerId) {
        try {
            return fileMetadataService.getTotalPendingFilesCount(receiverCustomerId);
        } catch (Exception e) {
            log.error("[FILE-DOWNLOAD-COMPONENT] Failed to get pending files count for receiver: {}", 
                    receiverCustomerId, e);
            return 0;
        }
    }

    /**
     * Clean up delivered files from disk and Redis.
     * 
     * @param receiverCustomerId Receiver's customer ID
     * @param deliveredFolders List of folder names that were successfully delivered
     */
    private void cleanupDeliveredFiles(String receiverCustomerId, List<String> deliveredFolders) {
        for (String folderName : deliveredFolders) {
            try {
                // Delete folder and files from disk
                fileStorageService.deleteFolder(folderName);
                log.info("[FILE-DOWNLOAD-COMPONENT] Deleted folder from disk: {}", folderName);
                
                // Remove metadata from Redis
                fileMetadataService.deleteFileMetadata(receiverCustomerId, folderName);
                log.info("[FILE-DOWNLOAD-COMPONENT] Removed metadata from Redis: {}", folderName);
                
            } catch (Exception e) {
                log.error("[FILE-DOWNLOAD-COMPONENT] Failed to cleanup folder: {}", folderName, e);
                // Continue cleanup for other folders
            }
        }
        
        log.info("[FILE-DOWNLOAD-COMPONENT] Cleanup completed for {} folders", deliveredFolders.size());
    }
}
