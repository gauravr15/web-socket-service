package com.odin.web_socket_service.service;

import com.odin.web_socket_service.dto.SendMessageResponse;

import java.util.List;

/**
 * Service interface for file download operations.
 * 
 * Defines the contract for retrieving files, encoding them, and managing cleanup.
 */
public interface IFileDownloadService {

    /**
     * Download all available files for the receiver.
     * 
     * Returns files as a list of SendMessageResponse objects, each representing
     * files from one sender in one upload session. Files are sorted by timestamp
     * (ascending). After successful delivery, files are deleted from disk and
     * metadata is removed from Redis.
     * 
     * @param receiverCustomerId Receiver's customer ID
     * @return List of SendMessageResponse objects with base64-encoded files
     * @throws IllegalStateException if file storage is disabled
     */
    List<SendMessageResponse> downloadFiles(String receiverCustomerId);

    /**
     * Check if files are available for the receiver.
     * 
     * @param receiverCustomerId Receiver's customer ID
     * @return true if files are available, false otherwise
     */
    boolean hasAvailableFiles(String receiverCustomerId);

    /**
     * Get total count of pending files for the receiver.
     * 
     * @param receiverCustomerId Receiver's customer ID
     * @return Total number of pending files
     */
    int getTotalPendingFilesCount(String receiverCustomerId);
}
