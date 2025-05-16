package com.assettrack.iot.session;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.assettrack.iot.session.cache.CacheManager;

import java.net.SocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    // Primary storage by IMEI
    private final Map<String, DeviceSession> sessionsByImei = new ConcurrentHashMap<>();
    // Secondary indexes
    private final Map<Long, DeviceSession> sessionsByDeviceId = new ConcurrentHashMap<>();
    private final Map<Channel, DeviceSession> sessionsByChannel = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DeviceSession>> sessionsByProtocol = new ConcurrentHashMap<>();

    @Autowired
    private CacheManager cacheManager;

    // Existing methods remain unchanged
    public DeviceSession getSessionByImei(String imei) {
        return sessionsByImei.get(imei);
    }

    public DeviceSession getSessionByDeviceId(long deviceId) {
        return sessionsByDeviceId.get(deviceId);
    }

    public DeviceSession getSessionByChannel(Channel channel) {
        return sessionsByChannel.get(channel);
    }

    public DeviceSession getSessionByProtocolAndImei(String protocol, String imei) {
        Map<String, DeviceSession> protocolSessions = sessionsByProtocol.get(protocol);
        return protocolSessions != null ? protocolSessions.get(imei) : null;
    }

    public DeviceSession createSession(long deviceId, String imei, String protocol,
                                       Channel channel, SocketAddress remoteAddress) {
        DeviceSession session = new DeviceSession(deviceId, imei, protocol, channel, remoteAddress);

        // Add to all indexes
        sessionsByImei.put(imei, session);
        sessionsByDeviceId.put(deviceId, session);
        sessionsByChannel.put(channel, session);
        sessionsByProtocol.computeIfAbsent(protocol, k -> new ConcurrentHashMap<>())
                .put(imei, session);

        cacheManager.addDevice(deviceId);
        return session;
    }

    // New methods to support required functionality
    public DeviceSession getOrCreateSession(String imei, String protocol, SocketAddress remoteAddress) {
        return sessionsByImei.compute(imei, (key, existing) -> {
            if (existing != null && !existing.isExpired()) {
                return existing;
            }
            // Create new session without channel (for non-Netty connections)
            long deviceId = generateDeviceId(imei);
            DeviceSession newSession = new DeviceSession(deviceId, imei, protocol, null, remoteAddress);
            sessionsByDeviceId.put(deviceId, newSession);
            sessionsByProtocol.computeIfAbsent(protocol, k -> new ConcurrentHashMap<>())
                    .put(imei, newSession);
            cacheManager.addDevice(deviceId);
            return newSession;
        });
    }

    public Optional<DeviceSession> getSession(String imei) {
        DeviceSession session = sessionsByImei.get(imei);
        if (session != null && session.isExpired()) {
            removeSession(imei);
            return Optional.empty();
        }
        return Optional.ofNullable(session);
    }

    public void closeSession(String sessionId) {
        // Assuming sessionId is the same as IMEI in this implementation
        removeSession(sessionId);
    }

    // Updated removeSession methods
    public void removeSession(String imei) {
        DeviceSession session = sessionsByImei.remove(imei);
        if (session != null) {
            sessionsByDeviceId.remove(session.getDeviceId());
            Channel channel = session.getChannel();
            if (channel != null) {
                sessionsByChannel.remove(channel);
            }

            Map<String, DeviceSession> protocolSessions = sessionsByProtocol.get(session.getProtocolType());
            if (protocolSessions != null) {
                protocolSessions.remove(imei);
            }

            cacheManager.removeDevice(session.getDeviceId());
        }
    }

    public void removeSession(Channel channel) {
        DeviceSession session = sessionsByChannel.remove(channel);
        if (session != null) {
            sessionsByImei.remove(session.getUniqueId());
            sessionsByDeviceId.remove(session.getDeviceId());

            Map<String, DeviceSession> protocolSessions = sessionsByProtocol.get(session.getProtocolType());
            if (protocolSessions != null) {
                protocolSessions.remove(session.getUniqueId());
            }

            cacheManager.removeDevice(session.getDeviceId());
        }
    }

    // Helper method to generate device ID from IMEI
    private long generateDeviceId(String imei) {
        return imei.hashCode() & 0xffffffffL; // Ensure positive long value
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessionsByImei.values().removeIf(session -> {
            if (session.isExpired()) {
                sessionsByDeviceId.remove(session.getDeviceId());
                Channel channel = session.getChannel();
                if (channel != null) {
                    sessionsByChannel.remove(channel);
                }

                Map<String, DeviceSession> protocolSessions = sessionsByProtocol.get(session.getProtocolType());
                if (protocolSessions != null) {
                    protocolSessions.remove(session.getUniqueId());
                }

                cacheManager.removeDevice(session.getDeviceId());
                return true;
            }
            return false;
        });
    }

    public int getActiveSessionCount() {
        return sessionsByImei.size();
    }

    /**
     * Adds a new device session to the session manager
     * @param session The device session to add
     * @throws IllegalArgumentException if session is null or missing required fields
     */
    public void addSession(DeviceSession session) {
        if (session == null) {
            throw new IllegalArgumentException("Session cannot be null");
        }

        String imei = session.getImei();
        if (imei == null || imei.trim().isEmpty()) {
            throw new IllegalArgumentException("Session IMEI cannot be null or empty");
        }

        Long deviceId = session.getDeviceId();
        if (deviceId == null) {
            throw new IllegalArgumentException("Session deviceId cannot be null");
        }

        // Synchronize to prevent concurrent modifications
        synchronized (this) {
            // Remove any existing session with the same IMEI
            DeviceSession existingSession = sessionsByImei.get(imei);
            if (existingSession != null) {
                removeSession(existingSession);
                logger.info("Replaced existing session for IMEI: {}", imei);
            }

            // Add to both maps
            sessionsByImei.put(imei, session);
            sessionsByChannel.put(session.getChannel(), session);

            logger.debug("Added new session for IMEI: {}, DeviceID: {}", imei, deviceId);
        }
    }

    public void removeSession(DeviceSession session) {
        if (session == null) return;

        synchronized (this) {
            sessionsByImei.remove(session.getImei());
            sessionsByChannel.remove(session.getChannel());
            logger.debug("Removed session for IMEI: {}", session.getImei());
        }
    }

}