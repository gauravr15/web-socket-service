package com.odin.web_socket_service.controller;

import com.odin.web_socket_service.constants.ApplicationConstants;
import com.odin.web_socket_service.dto.SendMessageResponse;
import com.odin.web_socket_service.service.IFileUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * REST Controller for file upload operations using HTTP/2.
 * 
 * Endpoint: POST /api/files/upload
 * Protocol: HTTP/2 (configured in application.properties)
 * Content-Type: multipart/form-data
 * 
 * Architecture:
 * Controller → Service → Component → Low-level Services
 * 
 * This controller handles HTTP concerns and delegates business logic to the service layer.
 */
@Slf4j
@RestController
@RequestMapping(ApplicationConstants.API_VERSION + ApplicationConstants.FILES)
public class FileUploadController {

    private final IFileUploadService fileUploadService;

    public FileUploadController(@Qualifier("fileUploadServiceImpl") IFileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    /**
     * Upload files endpoint.
     * 
     * @param files Array of files to upload
     * @param senderId Sender's customer ID
     * @param receiverId Receiver's customer ID
     * @param messageId Message ID for tracking (optional)
     * @param message Text message accompanying files (optional)
     * @param messageType Message type (default: FILE_UPLOAD)
     * @return SendMessageResponse with upload status
     */
    @PostMapping(value = ApplicationConstants.UPLOAD, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFiles(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("senderId") String senderId,
            @RequestParam("receiverId") String receiverId,
            @RequestParam(value = "messageId", required = false) String messageId,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "messageType", defaultValue = "FILE_UPLOAD") String messageType) {
        
        log.info("[FILE-UPLOAD-CONTROLLER] Upload request - Sender: {}, Receiver: {}, Files: {}", 
                senderId, receiverId, files.length);
        
        try {
            SendMessageResponse response = fileUploadService.uploadFiles(
                    files, senderId, receiverId, messageId, message, messageType
            );
            
            log.info("[FILE-UPLOAD-CONTROLLER] ✅ Upload successful");
            return ResponseEntity.ok(response);
            
        } catch (IllegalStateException e) {
            log.warn("[FILE-UPLOAD-CONTROLLER] Service unavailable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
                    
        } catch (IllegalArgumentException e) {
            log.error("[FILE-UPLOAD-CONTROLLER] ❌ Validation error: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
                    
        } catch (SecurityException e) {
            log.error("[FILE-UPLOAD-CONTROLLER] ❌ Security error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
                    
        } catch (Exception e) {
            log.error("[FILE-UPLOAD-CONTROLLER] ❌ Upload failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "File upload failed: " + e.getMessage()));
        }
    }
}
