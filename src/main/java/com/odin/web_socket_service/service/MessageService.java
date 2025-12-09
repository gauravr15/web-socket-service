package com.odin.web_socket_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.odin.web_socket_service.dto.CallSession;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;

@Service
@Slf4j
public class MessageService {

	private static class IceBuffer {
		boolean offerDelivered = false;
		boolean answerDelivered = false;
		final List<Map<String, Object>> queuedCandidates = new ArrayList<>();
	}

	private final Map<String, IceBuffer> iceBufferMap = new ConcurrentHashMap<>();

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final MessageRelayService messageRelayService;
	private final SessionRegistryService sessionRegistryService;
	private final CallSessionRegistryService callSessionRegistryService;
	private final ConnectionRegistryService connectionRegistryService;
	private final NotificationUtility notification;
	private final ProfileRepository profileRepo;
	private final CacheManager cacheManager; // Spring CacheManager injected
	private final OfflineMessageService offlineMessageService;
	private final KafkaNotificationService kafkaNotificationService;

	@Value("${offline.messaging.enabled:true}")
	private boolean offlineMessagingEnabled;

	@Value("${offline.message.storage.enabled:true}")
	private boolean offlineMessageStorageEnabled;

	@Value("${offline.kafka.notifications.enabled:true}")
	private boolean offlineKafkaNotificationsEnabled;

	public MessageService(MessageRelayService messageRelayService, SessionRegistryService sessionRegistryService,
			ConnectionRegistryService connectionRegistryService, NotificationUtility notification,
			ProfileRepository profileRepo, CacheManager cacheManager,
			CallSessionRegistryService callSessionRegistryService,
			OfflineMessageService offlineMessageService,
			KafkaNotificationService kafkaNotificationService) {
		this.messageRelayService = messageRelayService;
		this.sessionRegistryService = sessionRegistryService;
		this.connectionRegistryService = connectionRegistryService;
		this.notification = notification;
		this.profileRepo = profileRepo;
		this.cacheManager = cacheManager;
		this.callSessionRegistryService = callSessionRegistryService;
		this.offlineMessageService = offlineMessageService;
		this.kafkaNotificationService = kafkaNotificationService;
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
		long serviceEntryTime = System.currentTimeMillis();
		SendMessageRequest request = null;
		
		log.info("[DEBUG-SERVICE-ENTRY] MessageService.handleIncomingMessage() called - sessionId={}, timestamp={}", 
				session.getId(), serviceEntryTime);
		
		try {
			// Parse incoming payload into SendMessageRequest DTO
			log.info("[DEBUG-SERVICE-ENTRY] Incoming WS message payload length: {} bytes, timestamp={}",
					message.getPayload() != null ? message.getPayload().toString().length() : 0,
					System.currentTimeMillis());
			
			JsonNode root = objectMapper.readTree(message.getPayload().toString());
			log.debug("Parsed JSON root: {}", root.toString());

			// 1) WebRTC signaling messages come this way:
			if (root.has("signal")) {
				String signal = root.get("signal").asText();

				switch (signal) {
				case "CALL_OFFER":
				case "CALL_ANSWER":
				case "ICE_CANDIDATE":
				case "CALL_END":
				case "CALL_RINGING":
				case "CALL_REJECT":
				case "CALL_CONNECTED":
				case "CALL_BUSY":
				case "CALL_TIMEOUT":
				case "CALL_RENEGOTIATE":
				case "CALL_PARTICIPANT_ADD":
				case "CALL_PARTICIPANT_REMOVE":
					handleSignalWithProfile(signal, root);
					return;
				}
			}
			request = objectMapper.readValue(message.getPayload().toString(), SendMessageRequest.class);
			
			// Log if files are present
			if (request.getFiles() != null && !request.getFiles().isEmpty()) {
				log.info("Received message with {} file(s) from sender: {}", 
						request.getFiles().size(), request.getSenderId());
			}
		} catch (com.fasterxml.jackson.core.JsonParseException e) {
			log.error("JSON parsing error for message from session {}: {}", session.getId(), e.getMessage());
			throw e;
		} catch (Exception e) {
			log.error("Error handling incoming message from session {}: {}", session.getId(), e.getMessage(), e);
			throw e;
		}

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
		Profile sender = getOrLoadProfile(hashedSenderId, senderId);

		if (sender == null) {
			log.warn("Sender or Receiver not found for message {} → {}", senderId, receiverId);
			return;
		}

		// Check if there's any content to send (text message OR files)
		boolean hasActualMessage = actualMessage != null && !actualMessage.isEmpty();
		boolean hasSampleMessage = sampleMessage != null && !sampleMessage.isEmpty();
		boolean hasFiles = request.getFiles() != null && !request.getFiles().isEmpty();
		
		// Send in-app notification if sample message is present
		if (hasSampleMessage) {
			Map<String, String> map = new HashMap<>();
			map.put("sampleMessage", sampleMessage);
			NotificationDTO notify = NotificationDTO.builder()
					.notificationId(Long.valueOf(receiverId) + System.currentTimeMillis())
					.channel(NotificationChannel.INAPP).map(map).build();
			notification.sendOtpMessage(notify);
		}

		// Process message if it has actual content (text OR files)
		if (hasActualMessage || hasFiles) {
			WebSocketSession targetSession = sessionRegistryService.getSession(receiverId);

			// Build response with all content: actualMessage, sampleMessage, and files
			SendMessageResponse response = SendMessageResponse.builder()
					.senderMobile(sender.getMobile())
					.receiverId(receiverId)
					.messageId(request.getMessageId())
					.senderId(senderId)
					.delivered(targetSession != null && targetSession.isOpen())
					.deliveryTimestamp(timestamp)
					.actualMessage(actualMessage)  // Can be null/empty if only files
					.senderName(sender.getFirstName() + " " + sender.getLastName())
					.files(request.getFiles())  // Can be null/empty if only text
					.isRead(false)
					.messageType("chat")
					.timestamp(timestamp)
					.build();

			String outboundJson = objectMapper.writeValueAsString(response);
			
			// Log message details without exposing full payload (for large files)
			String logMessage = String.format("Outbound message [%s → %s]: text=%s, files=%d, size=%d bytes",
					senderId, receiverId, 
					hasActualMessage ? "yes" : "no",
					hasFiles ? request.getFiles().size() : 0,
					outboundJson.length());
			log.info(logMessage);

			if (targetSession != null && targetSession.isOpen()) {
				targetSession.sendMessage(new TextMessage(outboundJson));
				log.info("Forwarded message from {} → {} (text:{}, files:{})", 
						senderId, receiverId, hasActualMessage, hasFiles);
			} else {
				// Check if receiver is online in Redis
				boolean receiverOnline = connectionRegistryService.hasConnection(receiverId);
				if (!receiverOnline) {
					// Receiver is offline - handle offline message flow
					log.info("Receiver {} is offline, handling offline message flow", receiverId);
					handleOfflineMessage(receiverId, response, sampleMessage, senderId, timestamp);
				} else {
					// Receiver is online on another pod - relay via Redis
					log.info("Receiver {} is online on another pod, publishing via Redis", receiverId);
					messageRelayService.publish(senderId, receiverId, outboundJson);
				}
			}
		} else {
			log.warn("Invalid message payload from {}: {}", senderId, message.getPayload().toString());
		}
	}

	/**
	 * Small helper that serializes response and either sends to local session or
	 * publishes to Redis so other pods receive the whole signaling payload.
	 */
	private void forwardOrPublishSignal(String senderId, String receiverId, SendMessageResponse resp)
			throws IOException {
		String outboundJson = objectMapper.writeValueAsString(resp);
		WebSocketSession targetSession = sessionRegistryService.getSession(receiverId);

		if (targetSession != null && targetSession.isOpen()) {
			targetSession.sendMessage(new TextMessage(outboundJson));
			log.info("Forwarded signaling {} from {} → {}", resp.getMessageType(), senderId, receiverId);
		} else {
			// publish JSON so other pod can deliver the full signaling message
			messageRelayService.publish(senderId, receiverId, outboundJson);
			log.info("Published signaling {} from {} → {} to Redis (relay)", resp.getMessageType(), senderId,
					receiverId);
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
			log.debug("deliverMessage: msg length={} content={}", msg.length(), msg);

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
			log.debug("deliverMessage: msg length={} content={}", msg.length(), msg);

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
	 * Handles offline message delivery.
	 * Stores the message in Redis for later retrieval and publishes sampleMessage to Kafka.
	 * This is called when the receiver is not online.
	 */
	public void handleOfflineMessage(String receiverId, SendMessageResponse message, String sampleMessage, 
									  String senderId, Long timestamp) {
		try {
			// Check if offline messaging is enabled at all
			if (!offlineMessagingEnabled) {
				log.debug("Offline messaging is disabled via offline.messaging.enabled configuration");
				return;
			}

			if (receiverId == null || message == null) {
				log.warn("handleOfflineMessage: Invalid inputs - receiverId={}, message={}", receiverId, message);
				return;
			}

			log.info("Handling offline message for receiver={}, senderId={}, messageId={}", 
					receiverId, senderId, message.getMessageId());

			// 1. Store the complete message in Redis with configurable TTL for later retrieval
			// Only if offline message storage is enabled
			if (offlineMessageStorageEnabled) {
				offlineMessageService.storeUndeliveredMessage(receiverId, message);
				log.info("Stored undelivered message in Redis for receiver={}", receiverId);
			} else {
				log.debug("Offline message storage is disabled via offline.message.storage.enabled configuration");
			}

			// 2. Publish sampleMessage to Kafka for push notification
			// Only if offline Kafka notifications are enabled
			if (offlineKafkaNotificationsEnabled) {
				if (sampleMessage != null && !sampleMessage.isEmpty()) {
					kafkaNotificationService.publishNotification(
							receiverId, 
							senderId, 
							sampleMessage, 
							message.getMessageId(), 
							timestamp);
					log.info("Published notification to Kafka for receiver={}, sampleMessage length={}", 
							receiverId, sampleMessage.length());
				} else {
					log.warn("handleOfflineMessage: sampleMessage is empty/null for receiver={}", receiverId);
				}
			} else {
				log.debug("Offline Kafka notifications are disabled via offline.kafka.notifications.enabled configuration");
			}
		} catch (Exception e) {
			log.error("Failed to handle offline message for receiver={}: {}", receiverId, e.getMessage(), e);
		}
	}

	/**
	 * Compute (and cache) a global hash for a user identifier. Same hash across all
	 * receivers, deterministic & privacy-safe.
	 */
	public String hashUserIdentifier(String input) {
		log.info("Hashing user identifier: {}", input);
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
	public Profile getOrLoadProfile(String hashedId, String rawId) {
		log.info("Loading profile for rawId={} (hashed={})", rawId, hashedId);
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

	/*
	 * ========================================================== FIXED SIGNALING
	 * HANDLERS ==========================================================
	 */

	/**
	 * CALL_OFFER
	 */
	private void handleOfferJson(JsonNode root) throws IOException {
		String from = root.get("from").asText();
		String to = root.get("to").asText();
		JsonNode payload = root.get("payload");

		Map<String, Object> resp = new HashMap<>();
		resp.put("signal", "CALL_OFFER");
		resp.put("from", from);
		resp.put("to", to);
		resp.put("payload", payload);

		sendSignalJson(resp, from, to);
	}

	private void handleAnswerJson(JsonNode root) throws IOException {
		String from = root.get("from").asText();
		String to = root.get("to").asText();
		JsonNode payload = root.get("payload");

		Map<String, Object> resp = new HashMap<>();
		resp.put("signal", "CALL_ANSWER");
		resp.put("from", from);
		resp.put("to", to);
		resp.put("payload", payload);

		sendSignalJson(resp, from, to);
	}

	private void handleIceJson(JsonNode root) throws IOException {
		String from = root.get("from").asText();
		String to = root.get("to").asText();
		JsonNode payload = root.get("payload");

		Map<String, Object> resp = new HashMap<>();
		resp.put("signal", "ICE_CANDIDATE");
		resp.put("from", from);
		resp.put("to", to);
		resp.put("payload", payload);

		sendSignalJson(resp, from, to);
	}

	private void handleCallEndJson(JsonNode root) throws IOException {
		String from = root.get("from").asText();
		String to = root.get("to").asText();
		JsonNode payload = root.get("payload");

		Map<String, Object> resp = new HashMap<>();
		resp.put("signal", "CALL_END");
		resp.put("from", from);
		resp.put("to", to);
		resp.put("payload", payload);

		sendSignalJson(resp, from, to);
	}

	/*
	 * ========================================================== SHARED SENDER —
	 * forwards JSON safely to FE (or Redis)
	 * ==========================================================
	 */
	private void sendSignalJson(Map<String, Object> resp, String senderId, String receiverId) throws IOException {
		log.debug("Signal payload keys: {}", resp.keySet());
		String json = objectMapper.writeValueAsString(resp);

		WebSocketSession session = sessionRegistryService.getSession(receiverId);
		if (session != null && session.isOpen()) {
			session.sendMessage(new TextMessage(json));
			log.warn("SIGNAL SENT (LOCAL) {} → {} | {}", senderId, receiverId, resp.get("signal"));
		} else {
			messageRelayService.publish(senderId, receiverId, json);
			log.warn("SIGNAL SENT (REDIS) {} → {} | {}", senderId, receiverId, resp.get("signal"));
		}
	}

	private void handleSignalWithProfile(String signalType, JsonNode root) throws IOException {

		final String from = root.path("from").asText(null);
		final String to = root.path("to").asText(null);
		final JsonNode payload = root.path("payload");

		final String sessionId = root.path("sessionId").asText(null);
		final String callType = root.path("callType").asText(null);

		log.info("Received signal '{}' from {} → {} session={}", signalType, from, to, sessionId);

		// --- profile details ---
		Profile sender = null;
		if (from != null) {
			String hashed = hashUserIdentifier(from);
			sender = getOrLoadProfile(hashed, from);
		}

		// --- base response ---
		Map<String, Object> resp = new HashMap<>();
		resp.put("signal", signalType);
		resp.put("from", from);
		resp.put("to", to);
		if (payload != null && !payload.isMissingNode()) {
			resp.put("payload", payload);
		}
		if (sessionId != null)
			resp.put("sessionId", sessionId);
		if (callType != null)
			resp.put("callType", callType);

		if (sender != null) {
			resp.put("senderMobile", sender.getMobile());
			resp.put("senderName", sender.getFirstName() + " " + sender.getLastName());
		}

		// --- SESSION HANDLING ---
		CallSession session = (sessionId != null) ? callSessionRegistryService.getSession(sessionId) : null;

		IceBuffer buf = new IceBuffer();

		switch (signalType) {

		case "CALL_OFFER":
			session = callSessionRegistryService.createSession(sessionId, callType, from, to);
			session.setCurrentState("OFFERED");

			buf = iceBufferMap.computeIfAbsent(sessionId, k -> new IceBuffer());
			buf.offerDelivered = true;

			break;

		case "CALL_RINGING":
			safeUpdateState(session, sessionId, "RINGING");
			break;

		case "CALL_ANSWER":
			safeUpdateState(session, sessionId, "ANSWERED");

			buf = iceBufferMap.computeIfAbsent(sessionId, k -> new IceBuffer());
			buf.answerDelivered = true;

			// Forward any buffered ICE candidates
			for (Map<String, Object> c : buf.queuedCandidates) {
				sendSignalJson(c, from, to);
			}
			buf.queuedCandidates.clear();

			break;

		case "CALL_CONNECTED":
			if (safeUpdateState(session, sessionId, "CONNECTED")) {
				resp.put("state", "CONNECTED");
				resp.put("participants", session.getParticipants());
				resp.put("callType", session.getCallType());
			}
			break;

		case "CALL_RENEGOTIATE":
			if (!safeExists(session, sessionId))
				return;
			session.setCurrentState("RENEGOTIATING");
			resp.put("state", "RENEGOTIATING");
			resp.put("participants", session.getParticipants());
			resp.put("callType", session.getCallType());
			resp.put("renegotiate", true);
			break;

		case "CALL_REJECT":
			if (safeUpdateState(session, sessionId, "REJECTED")) {
				resp.put("state", "REJECTED");
			}
			callSessionRegistryService.markForCleanup(sessionId);
			break;

		case "CALL_END":
			if (safeUpdateState(session, sessionId, "ENDED")) {
				resp.put("state", "ENDED");
			}
			callSessionRegistryService.markForCleanup(sessionId);
			break;

		case "CALL_BUSY":
			safeUpdateState(session, sessionId, "BUSY");
			callSessionRegistryService.markForCleanup(sessionId);
			break;

		case "CALL_TIMEOUT":
			safeUpdateState(session, sessionId, "TIMEOUT");
			callSessionRegistryService.markForCleanup(sessionId);
			break;

		case "CALL_PARTICIPANT_ADD":
			if (safeExists(session, sessionId) && root.has("newParticipant")) {
				session.addParticipant(root.get("newParticipant").asText());
				resp.put("participants", session.getParticipants());
			}
			break;

		case "CALL_PARTICIPANT_REMOVE":
			if (safeExists(session, sessionId) && root.has("userId")) {
				session.removeParticipant(root.get("userId").asText());
				resp.put("participants", session.getParticipants());
			}
			break;
		case "ICE_CANDIDATE": {
			log.warn("Buffering ICE candidate for session {} (offerDelivered={}, answerDelivered={})", sessionId,
					buf.offerDelivered, buf.answerDelivered);

			buf = iceBufferMap.computeIfAbsent(sessionId, k -> new IceBuffer());

			boolean ready = buf.offerDelivered && buf.answerDelivered;

			Map<String, Object> candidateMsg = new HashMap<>();
			candidateMsg.put("signal", "ICE_CANDIDATE");
			candidateMsg.put("from", from);
			candidateMsg.put("to", to);
			candidateMsg.put("sessionId", sessionId);
			if (payload != null && !payload.isMissingNode()) {
				candidateMsg.put("payload", payload);
			}

			if (!ready) {
				log.warn("Buffering ICE candidate for session {} (not ready)", sessionId);
				buf.queuedCandidates.add(candidateMsg);
				return; // IMPORTANT: prevents sendSignalJson() from running
			}
			log.info("Sending ICE candidate from {} → {} for session {}", from, to, sessionId);

			sendSignalJson(candidateMsg, from, to);
			return; // IMPORTANT
		}

		}

		sendSignalJson(resp, from, to);
	}

	private boolean safeExists(CallSession s, String sessionId) {
		if (s == null) {
			log.warn("Signal received but session does NOT exist: {}", sessionId);
			return false;
		}
		return true;
	}

	private boolean safeUpdateState(CallSession session, String sessionId, String state) {
		if (!safeExists(session, sessionId))
			return false;
		callSessionRegistryService.updateState(sessionId, state);
		session.setCurrentState(state);
		log.info("Session {} state changed to {}", sessionId, state);
		return true;
	}

}
