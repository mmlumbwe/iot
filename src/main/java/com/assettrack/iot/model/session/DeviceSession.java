package com.assettrack.iot.model.session;

import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceSession {
    private final String imei;
    private final String protocol;
    private final SocketAddress remoteAddress;
    private final Instant creationTime;
    private volatile Instant lastActiveTime;
    private volatile Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile int sessionId;

    public DeviceSession(String imei, String protocol, SocketAddress remoteAddress) {
        this.imei = imei;
        this.protocol = protocol;
        this.remoteAddress = remoteAddress;
        this.creationTime = Instant.now();
        this.lastActiveTime = this.creationTime;
        this.sessionId = generateSessionId();
    }

    public void updateLastActive() {
        this.lastActiveTime = Instant.now();
    }

    public boolean isStale(Duration timeout) {
        return Instant.now().isAfter(lastActiveTime.plus(timeout));
    }

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

    private int generateSessionId() {
        return (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
    }

    // Additional methods for attribute management if needed
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }
}