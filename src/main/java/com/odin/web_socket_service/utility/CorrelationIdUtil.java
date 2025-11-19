package com.odin.web_socket_service.utility;

import org.slf4j.MDC;

import java.util.UUID;

public class CorrelationIdUtil {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    
    // Get the correlation ID from MDC
    public static String getCorrelationId() {
        return MDC.get(CORRELATION_ID_HEADER);
    }

    // Set correlation ID in MDC
    public static void setCorrelationId(String correlationId) {
        MDC.put(CORRELATION_ID_HEADER, correlationId);
    }

    // Generate a new correlation ID if none is present
    public static String generateCorrelationId() {
        return UUID.randomUUID().toString();
    }

    // Clear correlation ID from MDC after request processing
    public static void clear() {
        MDC.remove(CORRELATION_ID_HEADER);
    }
}
