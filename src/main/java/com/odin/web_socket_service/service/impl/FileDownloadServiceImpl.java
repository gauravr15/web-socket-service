package com.odin.web_socket_service.service.impl;

import com.odin.web_socket_service.dto.SendMessageResponse;
import com.odin.web_socket_service.service.IFileDownloadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service implementation for file download operations.
 * 
 * This service layer provides transaction management, security checks,
 * and delegates business logic to the component layer.
 */
@Slf4j
@Service("fileDownloadServiceImpl")
public class FileDownloadServiceImpl implements IFileDownloadService {

    private final IFileDownloadService fileDownloadComponent;

    public FileDownloadServiceImpl(IFileDownloadService fileDownloadComponent) {
        this.fileDownloadComponent = fileDownloadComponent;
    }

    @Override
    public List<SendMessageResponse> downloadFiles(String receiverCustomerId) {
        log.debug("[FILE-DOWNLOAD-SERVICE] Delegating download to component layer");
        
        // Delegate to component for business logic
        return fileDownloadComponent.downloadFiles(receiverCustomerId);
    }

    @Override
    public boolean hasAvailableFiles(String receiverCustomerId) {
        return fileDownloadComponent.hasAvailableFiles(receiverCustomerId);
    }

    @Override
    public int getTotalPendingFilesCount(String receiverCustomerId) {
        return fileDownloadComponent.getTotalPendingFilesCount(receiverCustomerId);
    }
}
