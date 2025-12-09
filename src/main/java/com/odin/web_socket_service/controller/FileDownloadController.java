package com.odin.web_socket_service.controller;

import com.odin.web_socket_service.constants.ApplicationConstants;
import com.odin.web_socket_service.dto.Auth;
import com.odin.web_socket_service.dto.SendMessageResponse;
import com.odin.web_socket_service.service.IFileDownloadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for file download operations using HTTP/2.
 * 
 * Endpoint: GET /api/files/download
 * Protocol: HTTP/2 (configured in application.properties)
 * 
 * Architecture:
 * Controller → Service → Component → Low-level Services
 * 
 * This controller handles HTTP concerns and delegates business logic to the service layer.
 */
@Slf4j
@RestController
@RequestMapping(ApplicationConstants.API_VERSION + ApplicationConstants.FILES)
public class FileDownloadController {

    private final IFileDownloadService fileDownloadService;

    public FileDownloadController(@Qualifier("fileDownloadServiceImpl") IFileDownloadService fileDownloadService) {
        this.fileDownloadService = fileDownloadService;
    }

    /**
     * Download files endpoint.
     * 
     * Returns all available files for the receiver as a list of SendMessageResponse objects.
     * Each response object represents files from one sender in one upload session.
     * 
     * @param receiverCustomerId Receiver's customer ID
     * @return List of SendMessageResponse objects sorted by timestamp
     */
    @PostMapping(ApplicationConstants.DOWNLOAD)
    public ResponseEntity<?> downloadFiles(
            @RequestBody Auth auth) {
        
        log.info("[FILE-DOWNLOAD-CONTROLLER] Download request for receiver: {}", String.valueOf(auth.getCustomerId()));
        
        try {
            List<SendMessageResponse> responseList = fileDownloadService.downloadFiles(String.valueOf(auth.getCustomerId()));
            
            log.info("[FILE-DOWNLOAD-CONTROLLER] ✅ Download successful - {} batches", responseList.size());
            return ResponseEntity.ok(responseList);
            
        } catch (IllegalStateException e) {
            log.warn("[FILE-DOWNLOAD-CONTROLLER] Service unavailable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
                    
        } catch (Exception e) {
            log.error("[FILE-DOWNLOAD-CONTROLLER] ❌ Download failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "File download failed: " + e.getMessage()));
        }
    }

    /**
     * Check if receiver has any available files for download.
     * 
     * @param receiverCustomerId Receiver's customer ID
     * @return Response with availability status
     */
    @PostMapping(ApplicationConstants.CHECK_AVAILABLE_FILES)
    public ResponseEntity<?> checkFileAvailability(
            @RequestBody Auth auth) {
        
        try {
            boolean hasFiles = fileDownloadService.hasAvailableFiles(String.valueOf(auth.getCustomerId()));
            int fileCount = fileDownloadService.getTotalPendingFilesCount(String.valueOf(auth.getCustomerId()));
            
            Map<String, Object> response = Map.of(
                    "available", hasFiles,
                    "totalFiles", fileCount
            );
            
            log.info("[FILE-DOWNLOAD-CONTROLLER] Availability check - Receiver: {}, Available: {}, Count: {}", 
            		String.valueOf(auth.getCustomerId()), hasFiles, fileCount);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("[FILE-DOWNLOAD-CONTROLLER] Failed to check availability", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to check file availability"));
        }
    }
}
