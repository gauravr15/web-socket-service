package com.odin.web_socket_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SendMessageRequest {
	private String senderId;
	private String receiverId;
	private String messageId;
	private String actualMessage;
	private String sampleMessage;
	private java.util.Map<String, String> files;  // Map<fileName, base64String>
	private Long timestamp;
	private String type;
	private String signal;

}
