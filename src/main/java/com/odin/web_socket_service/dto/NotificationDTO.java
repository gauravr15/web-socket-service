package com.odin.web_socket_service.dto;

import java.util.Map;

import com.odin.web_socket_service.enums.NotificationChannel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NotificationDTO {

	private Long customerId;
	
	private Long notificationId;
	
	private NotificationChannel channel;
	
	private Map<String, String> map;
	
	private String mobile;
	
	private String email;
	
}
