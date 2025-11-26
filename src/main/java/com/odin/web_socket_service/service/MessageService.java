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
	private final CallSessionRegistryService callSessionRegistryService;
	private final ConnectionRegistryService connectionRegistryService;
	private final NotificationUtility notification;
	private final ProfileRepository profileRepo;
	private final CacheManager cacheManager; // Spring CacheManager injected

	public MessageService(MessageRelayService messageRelayService, SessionRegistryService sessionRegistryService,
			ConnectionRegistryService connectionRegistryService, NotificationUtility notification,
			ProfileRepository profileRepo, CacheManager cacheManager,
			CallSessionRegistryService callSessionRegistryService) {
		this.messageRelayService = messageRelayService;
		this.sessionRegistryService = sessionRegistryService;
		this.connectionRegistryService = connectionRegistryService;
		this.notification = notification;
		this.profileRepo = profileRepo;
		this.cacheManager = cacheManager;
		this.callSessionRegistryService = callSessionRegistryService;
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
		log.info("Incoming WS message payload: {}",
				message.getPayload() != null ? message.getPayload().toString() : "null");
		JsonNode root = objectMapper.readTree(message.getPayload().toString());

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
		Profile sender = getOrLoadProfile(hashedSenderId, senderId);

		if (sender == null) {
			log.warn("Sender or Receiver not found for message {} → {}", senderId, receiverId);
			return;
		}

		// Existing chat flow (unchanged)
		if (sampleMessage != null && !sampleMessage.isEmpty()) {
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
			SendMessageResponse response = SendMessageResponse.builder().senderMobile(sender.getMobile())
					.receiverId(receiverId).messageId(request.getMessageId()).senderId(senderId)
					.delivered(targetSession != null && targetSession.isOpen()).deliveryTimestamp(timestamp)
					.actualMessage(actualMessage).senderName(sender.getFirstName() + " " + sender.getLastName())
					.isRead(false).messageType("chat").timestamp(timestamp).build();

			String outboundJson = objectMapper.writeValueAsString(response);
			log.info("Outbound signaling JSON [{} → {}]: {}", senderId != null ? senderId : "null",
					receiverId != null ? receiverId : "null", outboundJson != null ? outboundJson : "null");

			if (targetSession != null && targetSession.isOpen()) {
				targetSession.sendMessage(new TextMessage(outboundJson));
				log.info("Forwarded message from {} → {} (cached sender): {}", senderId, receiverId, actualMessage);
			} else {
				// publish JSON so other pods will deliver whole message
				messageRelayService.publish(senderId, receiverId, outboundJson);
				log.info("Receiver {} not local; published via Redis", receiverId);
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

		switch (signalType) {

		case "CALL_OFFER":
			session = callSessionRegistryService.createSession(sessionId, callType, from, to);
			session.setCurrentState("OFFERED");
			break;

		case "CALL_RINGING":
			safeUpdateState(session, sessionId, "RINGING");
			break;

		case "CALL_ANSWER":
			safeUpdateState(session, sessionId, "ANSWERED");
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
		return true;
	}

}
