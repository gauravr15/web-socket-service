package com.odin.web_socket_service.service;

import com.odin.web_socket_service.dto.SendMessageResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service interface for file upload operations.
 * 
 * Defines the contract for uploading files with metadata storage and notifications.
 */
public interface IFileUploadService {

    /**
     * Upload files with associated metadata.
     * 
     * @param files Array of files to upload
     * @param senderId Sender's customer ID
     * @param receiverId Receiver's customer ID
     * @param messageId Message ID for tracking (optional)
     * @param message Text message accompanying files (optional)
     * @param messageType Message type (default: FILE_UPLOAD)
     * @return SendMessageResponse with upload status and metadata
     * @throws IllegalStateException if file storage is disabled
     * @throws IllegalArgumentException if validation fails
     */
    SendMessageResponse uploadFiles(
            MultipartFile[] files,
            String senderId,
            String receiverId,
            String messageId,
            String message,
            String messageType
    );
}
