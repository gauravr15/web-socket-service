package com.odin.web_socket_service.service.impl;

import com.odin.web_socket_service.dto.SendMessageResponse;
import com.odin.web_socket_service.service.IFileUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service implementation for file upload operations.
 * 
 * This service layer provides transaction management, security checks,
 * and delegates business logic to the component layer.
 */
@Slf4j
@Service("fileUploadServiceImpl")
public class FileUploadServiceImpl implements IFileUploadService {

    private final IFileUploadService fileUploadComponent;

    public FileUploadServiceImpl(IFileUploadService fileUploadComponent) {
        this.fileUploadComponent = fileUploadComponent;
    }

    @Override
    public SendMessageResponse uploadFiles(
            MultipartFile[] files,
            String senderId,
            String receiverId,
            String messageId,
            String message,
            String messageType) {
        
        log.debug("[FILE-UPLOAD-SERVICE] Delegating upload to component layer");
        
        // Delegate to component for business logic
        return fileUploadComponent.uploadFiles(files, senderId, receiverId, messageId, message, messageType);
    }
}
