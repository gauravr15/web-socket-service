package com.odin.web_socket_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebRTCSignal {

    private String signal;        // CALL_INITIATE, CALL_OFFER, CALL_ANSWER, ICE_CANDIDATE, CALL_END
    private String callId;
    private String from;
    private String to;

    private SdpPayload sdp;       // optional
    private IceCandidatePayload ice;  // optional

}
