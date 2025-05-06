// SessionManager.java
package com.assettrack.iot.service.session;

import com.assettrack.iot.model.session.DeviceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    private static final long SESSION_TIMEOUT = 30;
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private volatile Duration sessionTimeout = Duration.ofHours(1);

    public DeviceSession getOrCreateSession(String imei, String protocol, SocketAddress remoteAddress) {
        if (imei == null || imei.isBlank()) {
            throw new IllegalArgumentException("IMEI cannot be null or blank");
        }

        return sessions.compute(imei, (key, existing) -> {
            if (existing == null) {
                DeviceSession newSession = new DeviceSession(imei, protocol, remoteAddress);
                logger.info("Created new session {} for {}", newSession.getSessionId(), imei);
                return newSession;
            }

            // Update existing session only if protocol matches
            if (existing.getProtocol().equals(protocol)) {
                existing.setRemoteAddress(remoteAddress);
                existing.updateLastActive();
                logger.debug("Updated existing session {} for {}", existing.getSessionId(), imei);
            } else {
                logger.warn("Protocol mismatch for IMEI {}: existing={}, new={}",
                        imei, existing.getProtocol(), protocol);
            }
            return existing;
        });
    }

    public DeviceSession getSession(String imei) {
        return sessions.get(imei);
    }

    public Collection<DeviceSession> getAllSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    @Scheduled(fixedRate = 300000) // 5 minutes
    public void cleanupStaleSessions() {
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue() == null || entry.getValue().isStale(sessionTimeout)) {
                logger.info("Removing stale session for IMEI: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    public void updateSession(DeviceSession session) {
        if (session != null && session.getImei() != null) {
            sessions.compute(session.getImei(), (k, v) -> {
                if (v == null || v.getSessionId() == session.getSessionId()) {
                    return session;
                }
                logger.warn("Session update conflict for IMEI: {}", session.getImei());
                return v; // Keep existing session if IDs don't match
            });
        }
    }

    public void setSessionTimeout(Duration sessionTimeout) {
        if (sessionTimeout != null) {
            this.sessionTimeout = sessionTimeout;
        }
    }

    public void onShutdown() {
    }

    public void closeSession(String sessionId) {
        sessions.remove(sessionId);
        logger.debug("Closed session {}", sessionId);
    }

}