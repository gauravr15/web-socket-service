package com.odin.web_socket_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for getting undelivered messages response.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UndeliveredMessagesResponse {
    
    private List<SendMessageResponse> messages;
    private Integer totalCount;
    private boolean hasMessages;
}
