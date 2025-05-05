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
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private Duration sessionTimeout = Duration.ofHours(1);

    public DeviceSession getOrCreateSession(String imei, String protocol, SocketAddress remoteAddress) {
        return sessions.compute(imei, (key, existing) -> {
            if (existing == null) {
                logger.info("Creating new session for IMEI: {}", imei);
                return new DeviceSession(imei, protocol, remoteAddress);
            }
            existing.updateLastActive();
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
            if (entry.getValue().isStale(sessionTimeout)) {
                logger.info("Removing stale session for IMEI: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
}