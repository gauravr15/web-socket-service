package com.odin.web_socket_service.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odin.web_socket_service.config.FileStorageProperties;
import com.odin.web_socket_service.dto.FileMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for managing file metadata in Redis.
 * 
 * Redis Key Structure:
 * - Key: {receiverCustomerId}:FILES
 * - Value: JSON array of FileMetadata objects
 * - TTL: Configured via file.storage.metadata-ttl-days
 * 
 * Example:
 * Key: customer456:FILES
 * Value: [
 *   {
 *     "folderName": "1733654400000_customer123",
 *     "senderCustomerId": "customer123",
 *     "creationTimestamp": 1733654400000,
 *     "fileNames": ["photo.jpg"],
 *     ...
 *   },
 *   {
 *     "folderName": "1733654500000_customer789",
 *     "senderCustomerId": "customer789",
 *     ...
 *   }
 * ]
 */
@Slf4j
@Service
public class FileMetadataService {

    private final StringRedisTemplate redisTemplate;
    private final FileStorageProperties fileStorageProperties;
    private final ObjectMapper objectMapper;

    public FileMetadataService(
            StringRedisTemplate redisTemplate,
            FileStorageProperties fileStorageProperties,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.fileStorageProperties = fileStorageProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate Redis key for file metadata.
     * Format: {receiverCustomerId}:FILES
     */
    private String generateRedisKey(String receiverCustomerId) {
        return receiverCustomerId + ":" + fileStorageProperties.getRedisKeyPrefix();
    }

    /**
     * Store file metadata in Redis by appending to existing list.
     * 
     * @param metadata FileMetadata to store
     */
    public void storeFileMetadata(FileMetadata metadata) {
        try {
            String redisKey = generateRedisKey(metadata.getReceiverCustomerId());
            
            // Get existing metadata list
            List<FileMetadata> existingMetadata = getFileMetadataList(metadata.getReceiverCustomerId());
            
            // Append new metadata
            existingMetadata.add(metadata);
            
            // Serialize to JSON
            String jsonValue = objectMapper.writeValueAsString(existingMetadata);
            
            // Store in Redis with TTL
            long ttlDays = fileStorageProperties.getMetadataTtlDays();
            redisTemplate.opsForValue().set(redisKey, jsonValue, ttlDays, TimeUnit.DAYS);
            
            log.info("[FILE-METADATA] Stored metadata for receiver={}, folder={}, filesCount={}, ttlDays={}", 
                    metadata.getReceiverCustomerId(), 
                    metadata.getFolderName(), 
                    metadata.getFileCount(),
                    ttlDays);
            
        } catch (Exception e) {
            log.error("[FILE-METADATA] Failed to store metadata for receiver={}, folder={}", 
                    metadata.getReceiverCustomerId(), 
                    metadata.getFolderName(), e);
            throw new RuntimeException("Failed to store file metadata in Redis", e);
        }
    }

    /**
     * Retrieve all file metadata for a receiver.
     * 
     * @param receiverCustomerId Receiver's customer ID
     * @return List of FileMetadata sorted by creationTimestamp (ascending)
     */
    public List<FileMetadata> getFileMetadataList(String receiverCustomerId) {
        try {
            String redisKey = generateRedisKey(receiverCustomerId);
            String jsonValue = redisTemplate.opsForValue().get(redisKey);
            
            if (jsonValue == null || jsonValue.isEmpty()) {
                log.debug("[FILE-METADATA] No metadata found for receiver={}", receiverCustomerId);
                return new ArrayList<>();
            }
            
            // Deserialize from JSON
            List<FileMetadata> metadataList = objectMapper.readValue(
                    jsonValue, 
                    new TypeReference<List<FileMetadata>>() {}
            );
            
            // Sort by timestamp (ascending) - oldest first
            metadataList = metadataList.stream()
                    .sorted((m1, m2) -> Long.compare(
                            m1.getCreationTimestamp(), 
                            m2.getCreationTimestamp()))
                    .collect(Collectors.toList());
            
            log.info("[FILE-METADATA] Retrieved {} metadata entries for receiver={}", 
                    metadataList.size(), receiverCustomerId);
            
            return metadataList;
            
        } catch (Exception e) {
            log.error("[FILE-METADATA] Failed to retrieve metadata for receiver={}", 
                    receiverCustomerId, e);
            return new ArrayList<>();
        }
    }

    /**
     * Delete specific folder metadata after successful delivery.
     * 
     * @param receiverCustomerId Receiver's customer ID
     * @param folderName Folder name to remove
     */
    public void deleteFileMetadata(String receiverCustomerId, String folderName) {
        try {
            String redisKey = generateRedisKey(receiverCustomerId);
            
            // Get existing metadata list
            List<FileMetadata> existingMetadata = getFileMetadataList(receiverCustomerId);
            
            // Remove the specified folder
            List<FileMetadata> updatedMetadata = existingMetadata.stream()
                    .filter(metadata -> !metadata.getFolderName().equals(folderName))
                    .collect(Collectors.toList());
            
            if (updatedMetadata.isEmpty()) {
                // If no metadata left, delete the key
                redisTemplate.delete(redisKey);
                log.info("[FILE-METADATA] Deleted entire key for receiver={} (no metadata left)", 
                        receiverCustomerId);
            } else {
                // Update Redis with remaining metadata
                String jsonValue = objectMapper.writeValueAsString(updatedMetadata);
                long ttlDays = fileStorageProperties.getMetadataTtlDays();
                redisTemplate.opsForValue().set(redisKey, jsonValue, ttlDays, TimeUnit.DAYS);
                
                log.info("[FILE-METADATA] Removed folder={} for receiver={}, remaining={}", 
                        folderName, receiverCustomerId, updatedMetadata.size());
            }
            
        } catch (Exception e) {
            log.error("[FILE-METADATA] Failed to delete metadata for receiver={}, folder={}", 
                    receiverCustomerId, folderName, e);
            throw new RuntimeException("Failed to delete file metadata from Redis", e);
        }
    }

    /**
     * Delete all file metadata for a receiver.
     * 
     * @param receiverCustomerId Receiver's customer ID
     */
    public void deleteAllFileMetadata(String receiverCustomerId) {
        try {
            String redisKey = generateRedisKey(receiverCustomerId);
            redisTemplate.delete(redisKey);
            
            log.info("[FILE-METADATA] Deleted all metadata for receiver={}", receiverCustomerId);
            
        } catch (Exception e) {
            log.error("[FILE-METADATA] Failed to delete all metadata for receiver={}", 
                    receiverCustomerId, e);
        }
    }

    /**
     * Check if receiver has any pending file downloads.
     * 
     * @param receiverCustomerId Receiver's customer ID
     * @return true if files are available for download
     */
    public boolean hasAvailableFiles(String receiverCustomerId) {
        List<FileMetadata> metadata = getFileMetadataList(receiverCustomerId);
        return !metadata.isEmpty();
    }

    /**
     * Get total count of pending files for a receiver.
     * 
     * @param receiverCustomerId Receiver's customer ID
     * @return Total number of files across all folders
     */
    public int getTotalPendingFilesCount(String receiverCustomerId) {
        List<FileMetadata> metadataList = getFileMetadataList(receiverCustomerId);
        return metadataList.stream()
                .mapToInt(FileMetadata::getFileCount)
                .sum();
    }
}
