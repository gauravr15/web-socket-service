package com.odin.web_socket_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odin.web_socket_service.dto.NotificationDTO;
import com.odin.web_socket_service.dto.Profile;
import com.odin.web_socket_service.dto.SendMessageRequest;
import com.odin.web_socket_service.dto.SendMessageResponse;
import com.odin.web_socket_service.repo.ProfileRepository;
import com.odin.web_socket_service.utility.NotificationUtility;
import lombok.extern.slf4j.Slf4j;
import om.odin.web_socket_service.enums.NotificationChannel;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class MessageService {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final MessageRelayService messageRelayService;
	private final SessionRegistryService sessionRegistryService;
	private final ConnectionRegistryService connectionRegistryService;
	private final NotificationUtility notification;
	private final ProfileRepository profileRepo;
	private final CacheManager cacheManager; // Spring CacheManager injected

	public MessageService(MessageRelayService messageRelayService, SessionRegistryService sessionRegistryService,
			ConnectionRegistryService connectionRegistryService, NotificationUtility notification,
			ProfileRepository profileRepo, CacheManager cacheManager) {
		this.messageRelayService = messageRelayService;
		this.sessionRegistryService = sessionRegistryService;
		this.connectionRegistryService = connectionRegistryService;
		this.notification = notification;
		this.profileRepo = profileRepo;
		this.cacheManager = cacheManager;
	}

	// small LRU for hash lookups (simple in-memory LRU)
	private final Map<String, String> hashCache = new LinkedHashMap<>(1000, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
			return size() > 1000; // keep last 1000 entries
		}
	};

	/**
	 * Handles messages received from local WebSocket clients (incoming ws traffic).
	 */
	public void handleIncomingMessage(WebSocketSession session, WebSocketMessage<?> message) throws IOException {
		// Parse incoming payload into SendMessageRequest DTO
		SendMessageRequest request = objectMapper.readValue(message.getPayload().toString(), SendMessageRequest.class);

		String senderId = request.getSenderId();
		String receiverId = request.getReceiverId();
		String actualMessage = null == request.getActualMessage() ? "" : request.getActualMessage().trim();
		String sampleMessage = null == request.getSampleMessage() ? "" : request.getSampleMessage().trim();
		Long timestamp = request.getTimestamp();

		if (ObjectUtils.isEmpty(senderId) || ObjectUtils.isEmpty(receiverId)) {
			log.warn("Missing senderId or receiverId in incoming payload: {}", message.getPayload().toString());
			return;
		}

		// Hash user identifiers globally (deterministic, same across receivers)
		String hashedSenderId = hashUserIdentifier(senderId);
//		String hashedReceiverId = hashUserIdentifier(receiverId);

		Profile sender = getOrLoadProfile(hashedSenderId, senderId);
//		Profile receiver = getOrLoadProfile(hashedReceiverId, receiverId);

		if (sender == null) {
			log.warn("Sender or Receiver not found for message {} → {}", senderId, receiverId);
			return;
		}

		if (sampleMessage != null) {
			Map<String, String> map = new HashMap<>();
			map.put("sampleMessage", sampleMessage);
			NotificationDTO notify = NotificationDTO.builder()
					.notificationId(Long.valueOf(receiverId) + System.currentTimeMillis())
					.channel(NotificationChannel.INAPP).map(map).build();
			notification.sendOtpMessage(notify);
		}

		if (actualMessage != null) {
			WebSocketSession targetSession = sessionRegistryService.getSession(receiverId);

			// Wrap outbound into SendMessageResponse DTO
			SendMessageResponse response = SendMessageResponse.builder().senderMobile(sender.getMobile()).receiverId(receiverId)
					.messageId(request.getMessageId()).senderId(senderId).delivered(targetSession != null && targetSession.isOpen())
					.deliveryTimestamp(timestamp).actualMessage(actualMessage).senderName(sender.getFirstName()+" "+sender.getLastName()).isRead(false).build();

			String outboundJson = objectMapper.writeValueAsString(response);

			if (targetSession != null && targetSession.isOpen()) {
				targetSession.sendMessage(new TextMessage(outboundJson));
				log.info("Forwarded message from {} → {} (cached sender): {}", senderId, receiverId, actualMessage);
			} else {
				messageRelayService.publish(senderId, receiverId, actualMessage);
				log.info("Receiver {} not local; published via Redis", receiverId);
			}
		} else {
			log.warn("Invalid message payload from {}: {}", senderId, message.getPayload().toString());
		}
	}

	/**
	 * Try to deliver a message for an HTTP-originated send request.
	 */
	public boolean deliverMessage(String fromUserId, String targetUserId, String msg) {
		try {
			if (targetUserId == null || msg == null) {
				log.warn("deliverMessage called with null target or msg (from={})", fromUserId);
				return false;
			}

			Optional<String> podOpt = connectionRegistryService.getConnectionPod(targetUserId);
			if (podOpt.isEmpty()) {
				log.info("deliverMessage: targetUserId={} is not present in Redis (offline)", targetUserId);
				return false;
			}

			String pod = podOpt.get();
			if (pod.equals(connectionRegistryService.getPodName())) {
				WebSocketSession localSession = sessionRegistryService.getSession(targetUserId);
				if (localSession != null && localSession.isOpen()) {
					localSession.sendMessage(new TextMessage(msg));
					log.info("deliverMessage: delivered local message {} → {} (pod={})", fromUserId, targetUserId, pod);
					return true;
				} else {
					log.warn(
							"deliverMessage: session missing for user {} even though Redis lists pod={}. Treating as offline.",
							targetUserId, pod);
					return false;
				}
			} else {
				messageRelayService.publish(fromUserId, targetUserId, msg);
				log.info("deliverMessage: published message from {} → {} to channel for pod={} (relay)", fromUserId,
						targetUserId, pod);
				return true;
			}
		} catch (IOException e) {
			log.error("deliverMessage: IOException while sending message to {}: {}", targetUserId, e.getMessage(), e);
			return false;
		} catch (Exception e) {
			log.error("deliverMessage: unexpected error for {} -> {}: {}", fromUserId, targetUserId, e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Handles cross-pod message delivery (called by Redis subscriber).
	 */
	public void deliverRemoteMessage(String targetUserId, String msg) {
		try {
			WebSocketSession targetSession = sessionRegistryService.getSession(targetUserId);
			if (targetSession != null && targetSession.isOpen()) {
				targetSession.sendMessage(new TextMessage(msg));
				log.info("Delivered cross-pod message to {}", targetUserId);
			} else {
				log.debug("deliverRemoteMessage: Target {} not connected on this pod", targetUserId);
			}
		} catch (IOException e) {
			log.error("Failed to deliver cross-pod message to {}: {}", targetUserId, e.getMessage(), e);
		} catch (Exception e) {
			log.error("Unexpected error delivering cross-pod message to {}: {}", targetUserId, e.getMessage(), e);
		}
	}

	/**
	 * Compute (and cache) a global hash for a user identifier. Same hash across all
	 * receivers, deterministic & privacy-safe.
	 */
	private String hashUserIdentifier(String input) {
		if (input == null)
			return null;
		synchronized (hashCache) {
			String cached = hashCache.get(input);
			if (cached != null)
				return cached;
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
			String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
			synchronized (hashCache) {
				hashCache.put(input, encoded);
			}
			return encoded;
		} catch (Exception e) {
			log.error("Hashing failed for {}: {}", input, e.getMessage());
			return input; // fallback to raw (shouldn’t happen)
		}
	}

	private String hashMaybeNull(String s) {
		return s == null ? null : hashUserIdentifier(s);
	}

	/**
	 * Load profile using Spring Cache (cache key = hashedId). If not in cache, load
	 * from repo and populate cache.
	 */
	private Profile getOrLoadProfile(String hashedId, String rawId) {
		if (hashedId == null || rawId == null)
			return null;

		Cache cache = cacheManager.getCache("profileCache");
		if (cache != null) {
			Profile cached = cache.get(hashedId, Profile.class);
			if (cached != null)
				return cached;
		}

		try {
			Profile profile = profileRepo.findByCustomerId(rawId);
			if (profile != null && cache != null) {
				cache.put(hashedId, profile);
			}
			return profile;
		} catch (Exception e) {
			log.error("Failed to load profile for {}: {}", rawId, e.getMessage());
			return null;
		}
	}
}
