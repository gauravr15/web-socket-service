package com.odin.web_socket_service.service;

import java.util.concurrent.*;

import org.springframework.stereotype.Service;

import com.odin.web_socket_service.dto.CallSession;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CallSessionRegistryService {

	private final ConcurrentHashMap<String, CallSession> sessions = new ConcurrentHashMap<>();
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

	// Cleanup delay after CALL_END / CALL_REJECT
	private static final long CLEANUP_DELAY_MS = 5000; // 5 seconds

	public CallSession createSession(String sessionId, String callType, String from, String to) {
		CallSession session = new CallSession(sessionId, callType, from, to);
		sessions.put(sessionId, session);
		log.info("CALL SESSION CREATED: {}", sessionId);
		return session;
	}

	public CallSession getSession(String sessionId) {
		return sessions.get(sessionId);
	}

	public void updateState(String sessionId, String state) {
		CallSession s = sessions.get(sessionId);
		if (s != null) {
			s.setCurrentState(state);
			log.info("CALL SESSION [{}] STATE UPDATED → {}", sessionId, state);
		}
	}

	public boolean sessionExists(String sessionId) {
		return sessions.containsKey(sessionId);
	}

	public void endSession(String sessionId) {
		sessions.remove(sessionId);
		log.info("CALL SESSION REMOVED: {}", sessionId);
	}

	public void markForCleanup(String sessionId) {
	    CallSession session = sessions.get(sessionId);

	    if (session == null) {
	        log.warn("markForCleanup: Session {} not found", sessionId);
	        return;
	    }

	    log.info("SESSION {} marked for cleanup in {} ms", sessionId, CLEANUP_DELAY_MS);

	    scheduler.schedule(() -> {
	        endSession(sessionId);
	    }, CLEANUP_DELAY_MS, TimeUnit.MILLISECONDS);
	}

}
