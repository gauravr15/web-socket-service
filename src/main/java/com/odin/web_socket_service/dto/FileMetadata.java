package com.odin.web_socket_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata for a single uploaded file stored in Redis.
 * 
 * This object is serialized as JSON and stored in a Redis list
 * under key: {receiverCustomerId}:FILES
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileMetadata {

    /**
     * Unique folder name where files are stored.
     * Format: {timestamp}_{senderCustomerId}
     * Example: 1733654400000_customer123
     */
    private String folderName;

    /**
     * Customer ID of the sender who uploaded the files.
     */
    private String senderCustomerId;

    /**
     * Customer ID of the receiver who will download the files.
     */
    private String receiverCustomerId;

    /**
     * Timestamp when the upload was created (in milliseconds).
     * Used by frontend for sorting (ascending order).
     */
    private Long creationTimestamp;

    /**
     * List of file names uploaded in this folder.
     * Example: ["photo1.jpg", "document.pdf"]
     */
    private java.util.List<String> fileNames;

    /**
     * Map of fileName -> fileExtension
     * Example: {"photo1.jpg": "jpg", "document.pdf": "pdf"}
     */
    private java.util.Map<String, String> fileExtensions;

    /**
     * Map of fileName -> fileSize (in bytes)
     * Example: {"photo1.jpg": 2048576, "document.pdf": 512000}
     */
    private java.util.Map<String, Long> fileSizes;

    /**
     * Total size of all files in this folder (in bytes).
     */
    private Long totalSize;

    /**
     * Number of files in this folder.
     */
    private Integer fileCount;

    /**
     * Message ID for tracking (optional).
     * Can be used to correlate with chat messages.
     */
    private String messageId;

    /**
     * Original message text sent along with files (optional).
     */
    private String message;

    /**
     * Message type (e.g., "FILE_UPLOAD").
     */
    private String messageType;
}
