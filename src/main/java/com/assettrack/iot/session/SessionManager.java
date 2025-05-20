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

    public synchronized DeviceSession getOrCreateSession(String imei, String protocol,
                                                         Channel channel, SocketAddress remoteAddress) {
        DeviceSession session = sessionsByImei.get(imei);

        if (session == null) {
            // Create new session
            long deviceId = generateDeviceId(imei);
            session = new DeviceSession(deviceId, imei, protocol, channel, remoteAddress);
            addSession(session);
            logger.info("Created new session for IMEI: {}", imei);
        } else if (session.isExpired()) {
            // Replace expired session
            removeSession(imei);
            long deviceId = generateDeviceId(imei);
            session = new DeviceSession(deviceId, imei, protocol, channel, remoteAddress);
            addSession(session);
            logger.info("Recreated expired session for IMEI: {}", imei);
        } else {
            // Update existing session
            session.setChannel(channel);
            session.setRemoteAddress(remoteAddress);
            session.updateLastActivity();
            logger.debug("Updated existing session for IMEI: {}", imei);
        }

        return session;
    }

    public DeviceSession getOrCreateSession(String imei, String protocol, SocketAddress remoteAddress) {
        // Create a temporary session without channel
        DeviceSession session = sessionsByImei.get(imei);
        if (session == null) {
            long deviceId = generateDeviceId(imei);
            session = new DeviceSession(deviceId, imei, protocol, null, remoteAddress);
            addSession(session);
        }
        return session;
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
        removeSession(sessionId);
    }

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
            sessionsByImei.remove(session.getImei());
            sessionsByDeviceId.remove(session.getDeviceId());

            Map<String, DeviceSession> protocolSessions = sessionsByProtocol.get(session.getProtocolType());
            if (protocolSessions != null) {
                protocolSessions.remove(session.getImei());
            }

            cacheManager.removeDevice(session.getDeviceId());
        }
    }

    private long generateDeviceId(String imei) {
        return imei.hashCode() & 0xffffffffL;
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
                    protocolSessions.remove(session.getImei());
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

        synchronized (this) {
            DeviceSession existingSession = sessionsByImei.get(imei);
            if (existingSession != null) {
                removeSession(existingSession);
                logger.info("Replaced existing session for IMEI: {}", imei);
            }

            sessionsByImei.put(imei, session);
            sessionsByDeviceId.put(deviceId, session);
            if (session.getChannel() != null) {
                sessionsByChannel.put(session.getChannel(), session);
            }
            sessionsByProtocol.computeIfAbsent(session.getProtocolType(), k -> new ConcurrentHashMap<>())
                    .put(imei, session);

            cacheManager.addDevice(deviceId);
            logger.debug("Added new session for IMEI: {}, DeviceID: {}", imei, deviceId);
        }
    }

    public void removeSession(DeviceSession session) {
        if (session == null) return;

        synchronized (this) {
            sessionsByImei.remove(session.getImei());
            sessionsByDeviceId.remove(session.getDeviceId());
            if (session.getChannel() != null) {
                sessionsByChannel.remove(session.getChannel());
            }

            Map<String, DeviceSession> protocolSessions = sessionsByProtocol.get(session.getProtocolType());
            if (protocolSessions != null) {
                protocolSessions.remove(session.getImei());
            }

            cacheManager.removeDevice(session.getDeviceId());
            logger.debug("Removed session for IMEI: {}", session.getImei());
        }
    }

    public boolean exists(String imei) {
        if (imei == null || imei.trim().isEmpty()) {
            return false;
        }
        return sessionsByImei.containsKey(imei);
    }

    public void create(String imei) {
        if (imei == null || imei.trim().isEmpty()) {
            throw new IllegalArgumentException("IMEI cannot be null or empty");
        }

        if (!exists(imei)) {
            long deviceId = generateDeviceId(imei);
            DeviceSession session = new DeviceSession(deviceId, imei, "UNKNOWN", null, null);
            addSession(session);
            logger.info("Created new session for IMEI: {}", imei);
        }
    }

    public boolean validateSession(String imei, Channel channel) {
        DeviceSession session = sessionsByImei.get(imei);
        if (session == null) {
            return false;
        }

        // Check if this is the same physical connection
        if (session.getChannel() != null &&
                !session.getChannel().id().equals(channel.id())) {
            logger.warn("Session exists but with different channel for IMEI: {}", imei);
            return false;
        }

        return !session.isExpired();
    }
}