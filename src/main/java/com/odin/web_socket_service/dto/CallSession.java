package com.odin.web_socket_service.dto;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
public class CallSession {

    private String sessionId;
    private String callType;         // audio/video
    private String initiatedBy;      // caller
    private String currentState;     // OFFERED, RINGING, CONNECTED, REJECTED, ENDED, TIMEOUT, BUSY
    private Set<String> participants = new HashSet<>();

    public CallSession(String sessionId, String callType, String initiatedBy, String receiverId) {
        this.sessionId = sessionId;
        this.callType = callType;
        this.initiatedBy = initiatedBy;
        this.participants.add(initiatedBy);
        this.participants.add(receiverId);
        this.currentState = "OFFERED";
    }

    public void addParticipant(String userId) {
        participants.add(userId);
    }

    public void removeParticipant(String userId) {
        participants.remove(userId);
    }
}
