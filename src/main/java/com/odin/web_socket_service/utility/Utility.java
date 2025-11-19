package com.odin.web_socket_service.utility;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odin.web_socket_service.dto.ResponseDTO;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class Utility {
	
	@Autowired
	private ObjectMapper objectMapper;
	
	@Autowired
	private RestTemplate restTemplate;
	

	public <D, E> E getAnInstance(D dto, Class<E> entityClass) {
		try {
			if (dto instanceof List<?>) {
				List<?> dtoList = getInstances(dto, entityClass);
				return dtoList.isEmpty() ? null : objectMapper.convertValue(dtoList.get(0), entityClass);
	        } else {
	            return objectMapper.convertValue(dto, entityClass);
	        }
		} catch (Exception e) {
			log.error("Error occured while converting to class entityClass : {}", ExceptionUtils.getStackTrace(e));
			return null;
		}
	}

	public <T> List<T> getInstances(Object data, Class<T> clazz) {
	    try {
	        if (data instanceof List<?>) {
	            return ((List<?>) data)
	                .stream()
	                .map(item -> getAnInstance(item, clazz))
	                .collect(Collectors.toList());
	        }
	        // Case 2: If data is a single object
	        else if (data != null) {
	            return Collections.singletonList(getAnInstance(data, clazz));
	        }
	    } catch (Exception e) {
	        log.error("Error occured while converting to class entityClass : {}", ExceptionUtils.getStackTrace(e));
	    }
	    return Collections.emptyList();
	}


    public <T, R> ResponseDTO makeRestCall(String url, T requestBody, HttpMethod httpMethod, Class<R> responseType) {
        try {
        	HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");

            // Add correlation ID to the headers
            String correlationId = CorrelationIdUtil.getCorrelationId();
            if (correlationId == null) {
                // Generate a new correlation ID if not present
                correlationId = CorrelationIdUtil.generateCorrelationId();
                CorrelationIdUtil.setCorrelationId(correlationId); // Optionally set it to MDC
            }
            headers.set("X-Correlation-ID", correlationId);
            headers.set("Content-Type", "application/json");
            HttpEntity<T> entity = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<ResponseDTO> response = restTemplate.exchange(url, httpMethod, entity, ResponseDTO.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            } else {
                throw new RuntimeException("Failed with HTTP error code : " + response.getStatusCode());
            }
        } catch (Exception e) {
            throw new RuntimeException("Error while making REST call", e);
        }
    }
    
    public <D, E> E dtoToEntity(D dto, Class<E> entityClass) {
        return objectMapper.convertValue(dto, entityClass);
    }

	public String getDeviceSignature(HttpServletRequest servlet) {
		return servlet.getHeader("deviceSignature");
	}

}
