package com.odin.web_socket_service.service;

import com.odin.web_socket_service.config.FileStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * Service for file system operations: upload, download, delete.
 * 
 * Directory Structure:
 * uploads/
 *   ├── 1733654400000_customer123/
 *   │   ├── photo1.jpg
 *   │   └── document.pdf
 *   ├── 1733654500000_customer789/
 *   │   └── video.mp4
 *   └── ...
 * 
 * Each folder is named: {timestamp}_{senderCustomerId}
 */
@Slf4j
@Service
public class FileStorageService {

    private final FileStorageProperties fileStorageProperties;
    private final Path uploadLocation;

    public FileStorageService(FileStorageProperties fileStorageProperties) {
        this.fileStorageProperties = fileStorageProperties;
        this.uploadLocation = Paths.get(fileStorageProperties.getUploadDir())
                .toAbsolutePath()
                .normalize();

        try {
            Files.createDirectories(this.uploadLocation);
            log.info("[FILE-STORAGE] Upload directory created/verified: {}", this.uploadLocation);
        } catch (IOException e) {
            log.error("[FILE-STORAGE] Failed to create upload directory: {}", this.uploadLocation, e);
            throw new RuntimeException("Could not create upload directory", e);
        }
    }

    /**
     * Generate unique folder name for this upload.
     * Format: {currentTimestamp}_{senderCustomerId}
     * 
     * @param senderCustomerId Sender's customer ID
     * @return Folder name
     */
    public String generateFolderName(String senderCustomerId) {
        long timestamp = System.currentTimeMillis();
        return timestamp + "_" + senderCustomerId;
    }

    /**
     * Store multiple files in a dedicated folder.
     * 
     * @param files Array of multipart files to store
     * @param folderName Folder name where files will be stored
     * @return Map of fileName -> storedFilePath
     * @throws IOException If file storage fails
     */
    public Map<String, String> storeFiles(MultipartFile[] files, String folderName) throws IOException {
        Map<String, String> storedFiles = new HashMap<>();
        
        // Create folder for this upload
        Path folderPath = this.uploadLocation.resolve(folderName);
        Files.createDirectories(folderPath);
        
        log.info("[FILE-STORAGE] Created folder: {}", folderPath);
        
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                log.warn("[FILE-STORAGE] Skipping empty file");
                continue;
            }
            
            // Validate file
            validateFile(file);
            
            // Get original filename and sanitize it
            String originalFilename = file.getOriginalFilename();
            String sanitizedFilename = sanitizeFilename(originalFilename);
            
            // Resolve file path
            Path destinationFile = folderPath.resolve(sanitizedFilename).normalize();
            
            // Security check: ensure file is inside the upload directory
            if (!destinationFile.getParent().equals(folderPath)) {
                throw new SecurityException("Cannot store file outside designated directory");
            }
            
            // Copy file to destination
            Files.copy(file.getInputStream(), destinationFile, StandardCopyOption.REPLACE_EXISTING);
            
            storedFiles.put(sanitizedFilename, destinationFile.toString());
            
            log.info("[FILE-STORAGE] Stored file: {} (size: {} bytes) in folder: {}", 
                    sanitizedFilename, file.getSize(), folderName);
        }
        
        return storedFiles;
    }

    /**
     * Retrieve all files from a specific folder as a map.
     * Key: fileName, Value: base64 encoded file content
     * 
     * @param folderName Folder name to read files from
     * @return Map of fileName -> base64EncodedContent
     * @throws IOException If file reading fails
     */
    public Map<String, String> retrieveFilesAsBase64(String folderName) throws IOException {
        Map<String, String> filesMap = new HashMap<>();
        
        Path folderPath = this.uploadLocation.resolve(folderName);
        
        if (!Files.exists(folderPath) || !Files.isDirectory(folderPath)) {
            log.warn("[FILE-STORAGE] Folder does not exist: {}", folderName);
            return filesMap;
        }
        
        try (Stream<Path> files = Files.list(folderPath)) {
            files.filter(Files::isRegularFile)
                    .forEach(filePath -> {
                        try {
                            byte[] fileBytes = Files.readAllBytes(filePath);
                            String base64Content = Base64.getEncoder().encodeToString(fileBytes);
                            String fileName = filePath.getFileName().toString();
                            
                            filesMap.put(fileName, base64Content);
                            
                            log.debug("[FILE-STORAGE] Read file: {} (size: {} bytes) from folder: {}", 
                                    fileName, fileBytes.length, folderName);
                            
                        } catch (IOException e) {
                            log.error("[FILE-STORAGE] Failed to read file: {}", filePath, e);
                        }
                    });
        }
        
        log.info("[FILE-STORAGE] Retrieved {} files from folder: {}", filesMap.size(), folderName);
        
        return filesMap;
    }

    /**
     * Delete entire folder and all its contents.
     * 
     * @param folderName Folder name to delete
     * @throws IOException If deletion fails
     */
    public void deleteFolder(String folderName) throws IOException {
        Path folderPath = this.uploadLocation.resolve(folderName);
        
        if (!Files.exists(folderPath)) {
            log.warn("[FILE-STORAGE] Folder does not exist (already deleted?): {}", folderName);
            return;
        }
        
        // Delete all files in folder first
        try (Stream<Path> files = Files.list(folderPath)) {
            files.forEach(filePath -> {
                try {
                    Files.delete(filePath);
                    log.debug("[FILE-STORAGE] Deleted file: {}", filePath.getFileName());
                } catch (IOException e) {
                    log.error("[FILE-STORAGE] Failed to delete file: {}", filePath, e);
                }
            });
        }
        
        // Delete folder itself
        Files.delete(folderPath);
        
        log.info("[FILE-STORAGE] Deleted folder: {}", folderName);
    }

    /**
     * Validate file size and extension.
     * 
     * @param file Multipart file to validate
     * @throws IllegalArgumentException If validation fails
     */
    private void validateFile(MultipartFile file) {
        // Check file size
        if (file.getSize() > fileStorageProperties.getMaxFileSize()) {
            throw new IllegalArgumentException(
                    String.format("File size exceeds maximum allowed size: %d bytes (max: %d bytes)", 
                            file.getSize(), fileStorageProperties.getMaxFileSize())
            );
        }
        
        // Check file extension
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isEmpty()) {
            throw new IllegalArgumentException("File name is empty");
        }
        
        String extension = getFileExtension(filename);
        
        // Check blocked extensions
        String blockedExtensions = fileStorageProperties.getBlockedExtensions();
        if (!blockedExtensions.isEmpty()) {
            List<String> blocked = Arrays.asList(blockedExtensions.toLowerCase().split(","));
            if (blocked.contains(extension.toLowerCase())) {
                throw new SecurityException(
                        String.format("File extension '%s' is not allowed for security reasons", extension)
                );
            }
        }
        
        // Check allowed extensions (if configured)
        String allowedExtensions = fileStorageProperties.getAllowedExtensions();
        if (!allowedExtensions.isEmpty()) {
            List<String> allowed = Arrays.asList(allowedExtensions.toLowerCase().split(","));
            if (!allowed.contains(extension.toLowerCase())) {
                throw new IllegalArgumentException(
                        String.format("File extension '%s' is not allowed. Allowed: %s", 
                                extension, allowedExtensions)
                );
            }
        }
    }

    /**
     * Sanitize filename to prevent path traversal attacks.
     * 
     * @param filename Original filename
     * @return Sanitized filename
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unnamed_file";
        }
        
        // Remove path separators and other dangerous characters
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Extract file extension from filename.
     * 
     * @param filename File name
     * @return Extension (without dot) or empty string
     */
    public String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
            return "";
        }
        
        return filename.substring(lastDotIndex + 1);
    }

    /**
     * Get file size in bytes.
     * 
     * @param folderName Folder name
     * @param fileName File name
     * @return File size in bytes, or 0 if file doesn't exist
     */
    public long getFileSize(String folderName, String fileName) {
        try {
            Path filePath = this.uploadLocation.resolve(folderName).resolve(fileName);
            if (Files.exists(filePath)) {
                return Files.size(filePath);
            }
        } catch (IOException e) {
            log.error("[FILE-STORAGE] Failed to get file size for {}/{}", folderName, fileName, e);
        }
        return 0L;
    }

    /**
     * Check if folder exists.
     * 
     * @param folderName Folder name
     * @return true if folder exists
     */
    public boolean folderExists(String folderName) {
        Path folderPath = this.uploadLocation.resolve(folderName);
        return Files.exists(folderPath) && Files.isDirectory(folderPath);
    }
}
