package com.assettrack.iot.session;

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
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(com.assettrack.iot.session.SessionManager.class);
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);
    private static final long CLEANUP_INTERVAL_MS = 60000;  // 5 minutes in milliseconds

    private final ConcurrentMap<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionCounter = new AtomicLong(0);
    private volatile Duration sessionTimeout = DEFAULT_SESSION_TIMEOUT;

    private final Map<String, DeviceSession> sessionsByImei = new ConcurrentHashMap<>();


    /**
     * Gets or creates a session for the specified device
     */
    public DeviceSession getOrCreateSession(String imei, String protocol, SocketAddress remoteAddress) {
        validateSessionParameters(imei, protocol, remoteAddress);

        return sessionsByImei.compute(imei, (key, existingSession) -> {
            if (existingSession != null) {
                // Update existing session
                existingSession.setRemoteAddress(remoteAddress);
                existingSession.updateLastActive();
                existingSession.incrementConnectionCount();
                return existingSession;
            } else {
                // Create new session
                DeviceSession newSession = new DeviceSession(imei, protocol, remoteAddress);
                sessions.put(newSession.getSessionId(), newSession);
                return newSession;
            }
        });
    }
    /**
     * Checks if a login request is a duplicate based on IMEI and serial number
     */
    public boolean isDuplicateLogin(String imei, short serialNumber) {
        DeviceSession session = sessionsByImei.get(imei);
        return session != null && session.isDuplicateSerialNumber(serialNumber) && !session.isStale();
    }

    /**
     * Checks if a packet is a duplicate based on sequence/serial numbers
     */
    public boolean isDuplicatePacket(String imei, short sequenceNumber, short serialNumber) {
        DeviceSession session = sessionsByImei.get(imei);
        return session != null && !session.isNewerPacket(sequenceNumber, serialNumber);
    }

    /**
     * Updates a session with new sequence and serial numbers
     */
    public void updateSessionNumbers(String imei, short sequenceNumber, short serialNumber) {
        DeviceSession session = sessionsByImei.get(imei);
        if (session != null) {
            session.updateActivity(sequenceNumber, serialNumber);
        }
    }


    private void validateSessionParameters(String imei, String protocol, SocketAddress remoteAddress) {
        if (imei == null || imei.isBlank()) {
            throw new IllegalArgumentException("IMEI cannot be null or blank");
        }
        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("Protocol cannot be null or blank");
        }
        if (remoteAddress == null) {
            throw new IllegalArgumentException("Remote address cannot be null");
        }
    }

    /*private DeviceSession createNewSession(String imei, String protocol, SocketAddress remoteAddress) {
        String sessionId = generateSessionId();
        return new DeviceSession(sessionId, imei, protocol, remoteAddress);
    }*/

    private DeviceSession updateExistingSession(DeviceSession existing, String protocol, SocketAddress remoteAddress) {
        if (!existing.getProtocol().equals(protocol)) {
            logger.warn("Protocol mismatch for IMEI {}: existing={}, new={}",
                    existing.getImei(), existing.getProtocol(), protocol);
            return existing;
        }

        existing.setRemoteAddress(remoteAddress);
        existing.updateLastActive();
        logger.debug("Updated existing session {} for IMEI {}", existing.getSessionId(), existing.getImei());
        return existing;
    }

    private String generateSessionId() {
        return String.format("%s-%d-%d",
                System.currentTimeMillis(),
                sessionCounter.incrementAndGet(),
                Thread.currentThread().getId());
    }

    /**
     * Retrieves a session by IMEI
     */
    public Optional<DeviceSession> getSession(String imei) {
        return Optional.ofNullable(sessions.get(imei));
    }

    /**
     * Returns all active sessions
     */
    public Collection<DeviceSession> getAllSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    /**
     * Scheduled cleanup of stale sessions
     */
    @Scheduled(fixedRate = CLEANUP_INTERVAL_MS)
    public void cleanupStaleSessions() {
        int initialSize = sessions.size();

        sessions.values().removeIf(session -> {
            if (session == null || session.isStale(sessionTimeout)) {
                sessionsByImei.remove(session.getImei(), session);
                logger.debug("Removed stale session for IMEI {}", session != null ? session.getImei() : "null");
                return true;
            }
            return false;
        });

        if (logger.isDebugEnabled() && sessions.size() != initialSize) {
            logger.debug("Session cleanup completed. Removed {} stale sessions", initialSize - sessions.size());
        }
    }


    /**
     * Updates an existing session
     */
    public void updateSession(DeviceSession session) {
        if (session == null || session.getImei() == null) {
            throw new IllegalArgumentException("Session and IMEI cannot be null");
        }

        sessions.compute(session.getImei(), (k, existing) -> {
            if (existing == null) {
                logger.warn("Attempted to update non-existent session for IMEI: {}", session.getImei());
                return session; // Allow creation if missing?
            }
            if (!existing.getSessionId().equals(session.getSessionId())) {
                logger.warn("Session ID mismatch for IMEI {}: existing={}, new={}",
                        session.getImei(), existing.getSessionId(), session.getSessionId());
                return existing;
            }
            return session;
        });
    }

    /**
     * Sets the session timeout duration
     */
    public void setSessionTimeout(Duration sessionTimeout) {
        if (sessionTimeout == null || sessionTimeout.isNegative() || sessionTimeout.isZero()) {
            throw new IllegalArgumentException("Session timeout must be a positive duration");
        }
        this.sessionTimeout = sessionTimeout;
        logger.info("Session timeout set to {}", sessionTimeout);
    }

    /**
     * Graceful shutdown handler
     */
    public void onShutdown() {
        logger.info("Shutting down SessionManager with {} active sessions", sessions.size());
        sessions.clear();
    }

    /**
     * Closes a session by session ID
     */
    public boolean closeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        return sessions.values().removeIf(session -> {
            if (session != null && sessionId.equals(session.getSessionId())) {
                logger.debug("Closed session {} for IMEI {}", sessionId, session.getImei());
                return true;
            }
            return false;
        });
    }

    /**
     * Closes all sessions for a specific IMEI
     */
    public boolean closeSessionsForImei(String imei) {
        if (imei == null || imei.isBlank()) {
            return false;
        }
        return sessions.remove(imei) != null;
    }

    /**
     * Returns the number of active sessions
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }
}
