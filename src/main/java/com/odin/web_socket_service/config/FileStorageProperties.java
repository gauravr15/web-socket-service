package com.odin.web_socket_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for file storage and upload/download operations.
 * 
 * These properties control file size limits, storage paths, and Redis TTL.
 * All values are externalized in application.properties for easy configuration.
 */
@Data
@Component
@ConfigurationProperties(prefix = "file.storage")
public class FileStorageProperties {

    /**
     * Base directory where uploaded files will be stored.
     * Each upload creates a subdirectory: {timestamp}_{senderCustomerId}
     * 
     * Example: /var/websocket-files/uploads/1733654400000_customer123/
     * 
     * Default: ./uploads
     */
    private String uploadDir = "./uploads";

    /**
     * Maximum size allowed for a single file in bytes.
     * 
     * Default: 104857600 (100 MB)
     * 
     * Note: This is separate from Spring's multipart.max-file-size which
     * controls the HTTP request parser. This property is used for business
     * validation and clearer error messages.
     */
    private long maxFileSize = 104857600L; // 100 MB

    /**
     * Maximum total size allowed for all files in a single upload request.
     * 
     * Default: 524288000 (500 MB)
     * 
     * Prevents clients from uploading hundreds of small files that exceed
     * reasonable storage limits.
     */
    private long maxTotalUploadSize = 524288000L; // 500 MB

    /**
     * Maximum number of files allowed in a single upload request.
     * 
     * Default: 50
     * 
     * Prevents abuse and ensures reasonable processing time.
     */
    private int maxFilesPerUpload = 50;

    /**
     * Redis TTL in days for file metadata entries.
     * 
     * Default: 7 days
     * 
     * After this period, undelivered files metadata will be removed from Redis.
     * The actual files on disk are removed after successful delivery.
     * 
     * Use this to clean up abandoned uploads where receiver never downloads.
     */
    private int metadataTtlDays = 7;

    /**
     * Redis key prefix for file metadata.
     * 
     * Default: FILES
     * 
     * Full key format: {receiverCustomerId}:{prefix}
     * Example: customer456:FILES
     */
    private String redisKeyPrefix = "FILES";

    /**
     * Whether to enable file upload/download functionality.
     * 
     * Default: true
     * 
     * Set to false to disable file operations without code changes.
     */
    private boolean enabled = true;

    /**
     * Allowed file extensions (comma-separated).
     * Empty means all extensions are allowed.
     * 
     * Example: jpg,png,pdf,docx,zip
     * 
     * Default: empty (all extensions allowed)
     */
    private String allowedExtensions = "";

    /**
     * Blocked file extensions (comma-separated) for security.
     * 
     * Example: exe,bat,sh,cmd
     * 
     * Default: exe,bat,sh,cmd,ps1,vbs,jar
     */
    private String blockedExtensions = "exe,bat,sh,cmd,ps1,vbs,jar";
}
