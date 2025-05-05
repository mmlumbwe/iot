package com.assettrack.iot.model.session;

import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceSession {
    private final String imei;
    private final String protocol;
    private volatile SocketAddress remoteAddress;  // Made volatile for thread-safety
    private final Instant creationTime;
    private volatile Instant lastActiveTime;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final int sessionId;

    public DeviceSession(String imei, String protocol, SocketAddress remoteAddress) {
        if (imei == null || imei.isBlank()) {
            throw new IllegalArgumentException("IMEI cannot be null or blank");
        }
        this.imei = imei;
        this.protocol = protocol;
        this.remoteAddress = remoteAddress;
        this.creationTime = Instant.now();
        this.lastActiveTime = this.creationTime;
        this.sessionId = generateSessionId();
    }

    // Thread-safe update methods
    public synchronized void updateLastActive() {
        this.lastActiveTime = Instant.now();
    }

    public synchronized void setRemoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
        updateLastActive();  // Update activity timestamp when address changes
    }

    public boolean isStale(Duration timeout) {
        return Instant.now().isAfter(lastActiveTime.plus(timeout));
    }

    // Immutable getters
    public String getImei() {
        return imei;
    }

    public String getProtocol() {
        return protocol;
    }

    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public Instant getLastActiveTime() {
        return lastActiveTime;
    }

    public int getSessionId() {
        return sessionId;
    }

    // Attribute management
    public void setAttribute(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("Attribute key cannot be null");
        }
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public Object removeAttribute(String key) {
        return attributes.remove(key);
    }

    // Private helper
    private int generateSessionId() {
        return (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
    }

    @Override
    public String toString() {
        return "DeviceSession{" +
                "imei='" + imei + '\'' +
                ", protocol='" + protocol + '\'' +
                ", sessionId=" + sessionId +
                ", created=" + creationTime +
                ", lastActive=" + lastActiveTime +
                '}';
    }
}