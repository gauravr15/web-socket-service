package com.odin.web_socket_service.component;

import com.odin.web_socket_service.config.FileStorageProperties;
import com.odin.web_socket_service.dto.FileMetadata;
import com.odin.web_socket_service.dto.SendMessageResponse;
import com.odin.web_socket_service.service.FileMetadataService;
import com.odin.web_socket_service.service.FileNotificationService;
import com.odin.web_socket_service.service.FileStorageService;
import com.odin.web_socket_service.service.IFileUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * Component for handling file upload business logic.
 * 
 * Responsibilities:
 * - Validate upload requests
 * - Coordinate file storage operations
 * - Build and store metadata
 * - Trigger notifications
 */
@Slf4j
@Component
public class FileUploadComponent implements IFileUploadService {

    private final FileStorageService fileStorageService;
    private final FileMetadataService fileMetadataService;
    private final FileNotificationService fileNotificationService;
    private final FileStorageProperties fileStorageProperties;

    public FileUploadComponent(
            FileStorageService fileStorageService,
            FileMetadataService fileMetadataService,
            FileNotificationService fileNotificationService,
            FileStorageProperties fileStorageProperties) {
        this.fileStorageService = fileStorageService;
        this.fileMetadataService = fileMetadataService;
        this.fileNotificationService = fileNotificationService;
        this.fileStorageProperties = fileStorageProperties;
    }

    @Override
    public SendMessageResponse uploadFiles(
            MultipartFile[] files,
            String senderId,
            String receiverId,
            String messageId,
            String message,
            String messageType) {
        
        long startTime = System.currentTimeMillis();
        
        log.info("[FILE-UPLOAD-COMPONENT] Processing upload - Sender: {}, Receiver: {}, Files: {}", 
                senderId, receiverId, files.length);
        
        // Check if feature is enabled
        if (!fileStorageProperties.isEnabled()) {
            throw new IllegalStateException("File upload service is currently unavailable");
        }
        
        // Validate files
        validateUploadRequest(files);
        
        // Generate unique folder name
        String folderName = fileStorageService.generateFolderName(senderId);
        long creationTimestamp = System.currentTimeMillis();
        
        log.info("[FILE-UPLOAD-COMPONENT] Generated folder: {}", folderName);
        
        // Store files in file system
        Map<String, String> storedFiles;
        try {
            storedFiles = fileStorageService.storeFiles(files, folderName);
        } catch (java.io.IOException e) {
            log.error("[FILE-UPLOAD-COMPONENT] Failed to store files", e);
            throw new RuntimeException("Failed to store files: " + e.getMessage(), e);
        }
        
        // Build metadata
        FileMetadata metadata = buildFileMetadata(
                files, 
                folderName, 
                senderId, 
                receiverId, 
                creationTimestamp,
                messageId,
                message,
                messageType
        );
        
        log.info("[FILE-UPLOAD-COMPONENT] Built metadata - SenderId: {}, ReceiverId: {}, MessageId: {}", 
                metadata.getSenderCustomerId(), metadata.getReceiverCustomerId(), metadata.getMessageId());
        
        // Store metadata in Redis
        fileMetadataService.storeFileMetadata(metadata);
        
        log.info("[FILE-UPLOAD-COMPONENT] Stored {} files, metadata saved to Redis", storedFiles.size());
        
        // Notify receiver via WebSocket (if online)
        fileNotificationService.notifyReceiverAboutFiles(metadata);
        
        // Build response
        SendMessageResponse response = SendMessageResponse.builder()
                .senderId(senderId)
                .receiverId(receiverId)
                .messageId(messageId != null ? messageId : UUID.randomUUID().toString())
                .timestamp(creationTimestamp)
                .messageType(messageType != null ? messageType : "FILE_UPLOAD")
                .actualMessage(message)
                .delivered(false)
                .build();
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("[FILE-UPLOAD-COMPONENT] ✅ Upload completed in {}ms - Folder: {}, Files: {}, Size: {} bytes", 
                duration, folderName, metadata.getFileCount(), metadata.getTotalSize());
        
        return response;
    }

    /**
     * Validate upload request.
     * 
     * Checks:
     * 1. Files are not null or empty
     * 2. File count doesn't exceed limit
     * 3. Total size doesn't exceed limit
     * 
     * @param files Files to validate
     * @throws IllegalArgumentException If validation fails
     */
    private void validateUploadRequest(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("No files provided");
        }
        
        // Check file count
        if (files.length > fileStorageProperties.getMaxFilesPerUpload()) {
            throw new IllegalArgumentException(
                    String.format("Too many files. Maximum allowed: %d, provided: %d",
                            fileStorageProperties.getMaxFilesPerUpload(), files.length)
            );
        }
        
        // Check total size
        long totalSize = Arrays.stream(files)
                .mapToLong(MultipartFile::getSize)
                .sum();
        
        if (totalSize > fileStorageProperties.getMaxTotalUploadSize()) {
            throw new IllegalArgumentException(
                    String.format("Total upload size exceeds limit. Maximum: %d bytes, provided: %d bytes",
                            fileStorageProperties.getMaxTotalUploadSize(), totalSize)
            );
        }
        
        log.debug("[FILE-UPLOAD-COMPONENT] Validation passed - Files: {}, Total size: {} bytes", 
                files.length, totalSize);
    }

    /**
     * Build file metadata object.
     * 
     * @param files Uploaded files
     * @param folderName Folder name
     * @param senderId Sender's customer ID
     * @param receiverId Receiver's customer ID
     * @param timestamp Creation timestamp
     * @param messageId Message ID
     * @param message Text message
     * @param messageType Message type
     * @return FileMetadata
     */
    private FileMetadata buildFileMetadata(
            MultipartFile[] files,
            String folderName,
            String senderId,
            String receiverId,
            Long timestamp,
            String messageId,
            String message,
            String messageType) {
        
        List<String> fileNames = new ArrayList<>();
        Map<String, String> fileExtensions = new HashMap<>();
        Map<String, Long> fileSizes = new HashMap<>();
        long totalSize = 0L;
        
        for (MultipartFile file : files) {
            if (!file.isEmpty()) {
                String fileName = file.getOriginalFilename();
                String extension = fileStorageService.getFileExtension(fileName);
                long size = file.getSize();
                
                fileNames.add(fileName);
                fileExtensions.put(fileName, extension);
                fileSizes.put(fileName, size);
                totalSize += size;
            }
        }
        
        return FileMetadata.builder()
                .folderName(folderName)
                .senderCustomerId(senderId)
                .receiverCustomerId(receiverId)
                .creationTimestamp(timestamp)
                .fileNames(fileNames)
                .fileExtensions(fileExtensions)
                .fileSizes(fileSizes)
                .totalSize(totalSize)
                .fileCount(fileNames.size())
                .messageId(messageId != null ? messageId : UUID.randomUUID().toString())
                .message(message)
                .messageType(messageType != null ? messageType : "FILE_UPLOAD")
                .build();
    }
}
