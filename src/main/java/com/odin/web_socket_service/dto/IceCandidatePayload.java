package com.odin.web_socket_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class IceCandidatePayload {
    private String candidate;
    private String sdpMid;
    private Integer sdpMLineIndex;
}
