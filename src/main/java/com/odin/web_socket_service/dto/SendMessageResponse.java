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
public class SendMessageResponse {
	
    private boolean delivered;
    private Long deliveryTimestamp;
    private Long readTimestamp;
    private boolean isRead;
    private String senderId;
    private String senderMobile;
	private String receiverId;
	private String messageId;
	private String actualMessage;
	private Object file;
	private Long timestamp;
    private String senderName;
    
    private String messageType;   // chat, call-offer, call-answer, ice, call-end
    private String sdp;           // SDP for offer/answer
    private String candidate;     // ICE candidate JSON
    private String callId;
    
    private String eventType;   // CALL_INITIATE, OFFER, ANSWER, ICE, CALL_END, etc.

    private Boolean videoCall;  // audio / video

}
