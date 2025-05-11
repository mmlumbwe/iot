package com.assettrack.iot.model.session;

import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class DeviceSession {
    private final AtomicReference<String> imei = new AtomicReference<>();
    private final String protocol;
    private final AtomicReference<SocketAddress> remoteAddress = new AtomicReference<>();
    private final Instant creationTime;
    private volatile Instant lastActiveTime;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final String sessionId;
    private volatile short lastSequenceNumber = -1;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private volatile short lastSerialNumber = -1;
    private volatile int connectionCount = 1;

    // Constants
    private static final int IMEI_LENGTH = 15;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    private static final short MAX_SEQUENCE_NUMBER = Short.MAX_VALUE;
    private static final short SEQUENCE_ROLLOVER_THRESHOLD = (short)(MAX_SEQUENCE_NUMBER - 1000);
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofMinutes(2); // 2 minutes

    public DeviceSession(String imei, String protocol, SocketAddress remoteAddress) {
        this(imei, protocol, remoteAddress, generateSessionId(imei));
    }

    public DeviceSession(String imei, String protocol, SocketAddress remoteAddress, String sessionId) {
        this.imei.set(validateImei(imei));
        this.protocol = validateProtocol(protocol);
        this.remoteAddress.set(remoteAddress);
        this.creationTime = Instant.now();
        this.lastActiveTime = this.creationTime;
        this.sessionId = validateSessionId(sessionId);
    }

    // Validation methods
    private static String validateImei(String imei) {
        if (imei == null || imei.isBlank()) {
            throw new IllegalArgumentException("IMEI cannot be null or blank");
        }

        // Normalize IMEI
        String normalized = imei.trim().replaceAll("[^0-9]", "");

        // Remove leading zeros
        while (normalized.startsWith("0") && normalized.length() > 1) {
            normalized = normalized.substring(1);
        }

        if (normalized.length() != IMEI_LENGTH || !normalized.matches("\\d+")) {
            throw new IllegalArgumentException(
                    String.format("Invalid IMEI format. Expected %d digits, got: %s",
                            IMEI_LENGTH, imei));
        }

        return normalized;
    }

    private static String validateProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("Protocol cannot be null or blank");
        }
        return protocol.trim().toUpperCase();
    }

    private static String validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID cannot be null or blank");
        }
        return sessionId.trim();
    }

    private static String generateSessionId(String imei) {
        return String.format("%s-%d-%d",
                imei,
                System.currentTimeMillis(),
                Thread.currentThread().getId());
    }

    // Session state management
    public boolean isActive() {
        return active.get();
    }

    public void activate() {
        active.set(true);
        updateLastActive();
    }

    public void deactivate() {
        active.set(false);
    }

    public void updateLastActive() {
        this.lastActiveTime = Instant.now();
    }

    public void incrementConnectionCount() {
        this.connectionCount++;
        updateLastActive();
    }

    public int getConnectionCount() {
        return connectionCount;
    }

    // Sequence number handling
    public synchronized boolean updateSequenceNumber(short sequenceNumber) {
        // Handle sequence number rollover
        if (lastSequenceNumber > SEQUENCE_ROLLOVER_THRESHOLD && sequenceNumber < 100) {
            // Sequence number has rolled over
            lastSequenceNumber = sequenceNumber;
            updateLastActive();
            return true;
        }

        // Normal sequence number check
        if (sequenceNumber > lastSequenceNumber) {
            lastSequenceNumber = sequenceNumber;
            updateLastActive();
            return true;
        }

        return false;
    }

    // Serial number handling (for GT06 protocol)
    public synchronized void updateSerialNumber(short serialNumber) {
        this.lastSerialNumber = serialNumber;
        updateLastActive();
    }

    public synchronized boolean isDuplicateSerialNumber(short serialNumber) {
        return this.lastSerialNumber == serialNumber && !isExpired();
    }

    public boolean isExpired() {
        return isExpired(DEFAULT_SESSION_TIMEOUT);
    }

    public boolean isExpired(Duration timeout) {
        return Instant.now().isAfter(lastActiveTime.plus(timeout));
    }

    // Session timeout handling
    public boolean isStale() {
        return isStale(DEFAULT_TIMEOUT);
    }

    public boolean isStale(Duration timeout) {
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        }
        return Instant.now().isAfter(lastActiveTime.plus(timeout));
    }

    // Getters
    public String getImei() {
        return imei.get();
    }

    public String getProtocol() {
        return protocol;
    }

    public SocketAddress getRemoteAddress() {
        return remoteAddress.get();
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public Instant getLastActiveTime() {
        return lastActiveTime;
    }

    public String getSessionId() {
        return sessionId;
    }

    public short getLastSequenceNumber() {
        return lastSequenceNumber;
    }

    public short getLastSerialNumber() {
        return lastSerialNumber;
    }

    // Setters with validation
    public void setImei(String imei) {
        this.imei.set(validateImei(imei));
        updateLastActive();
    }

    public void setRemoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress.set(remoteAddress);
        updateLastActive();
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

    @Override
    public String toString() {
        return "DeviceSession{" +
                "imei='" + imei.get() + '\'' +
                ", protocol='" + protocol + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", created=" + creationTime +
                ", lastActive=" + lastActiveTime +
                ", active=" + active +
                ", connectionCount=" + connectionCount +
                '}';
    }

    // Additional GT06-specific methods
    public synchronized boolean isNewerPacket(short sequenceNumber, short serialNumber) {
        // First check serial number (GT06 protocol specific)
        if (serialNumber != -1 && serialNumber != lastSerialNumber) {
            return true;
        }

        // Then check sequence number
        return sequenceNumber > lastSequenceNumber;
    }

    public synchronized void updateActivity(short sequenceNumber, short serialNumber) {
        if (serialNumber != -1) {
            this.lastSerialNumber = serialNumber;
        }
        if (sequenceNumber != -1) {
            this.lastSequenceNumber = sequenceNumber;
        }
        this.lastActiveTime = Instant.now();
    }
}