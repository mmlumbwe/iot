package com.assettrack.iot.model.session;

import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class DeviceSession {
    // Constants
    private static final int IMEI_LENGTH = 15;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);  // Increased from 2 to 5 minutes
    private static final short MAX_SEQUENCE_NUMBER = Short.MAX_VALUE;
    private static final short SEQUENCE_ROLLOVER_THRESHOLD = (short)(MAX_SEQUENCE_NUMBER - 1000);
    private static final short INVALID_SERIAL = -1;
    private static final short INVALID_SEQUENCE = -1;

    // Session state
    private final AtomicReference<String> imei = new AtomicReference<>();
    private final String protocol;
    private final AtomicReference<SocketAddress> remoteAddress = new AtomicReference<>();
    private final Instant creationTime;
    private volatile Instant lastActiveTime;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final String sessionId;
    private final AtomicInteger connectionCount = new AtomicInteger(1);
    private final AtomicBoolean connected = new AtomicBoolean(true);

    // Protocol-specific state (GT06)
    private volatile short lastSequenceNumber = INVALID_SEQUENCE;
    private volatile short lastSerialNumber = INVALID_SERIAL;

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

        String normalized = imei.trim().replaceAll("[^0-9]", "");

        // Remove leading zeros but ensure we maintain 15 digits
        while (normalized.length() > IMEI_LENGTH && normalized.startsWith("0")) {
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

    // Connection state management
    public boolean isConnected() {
        return connected.get() && !isExpired();
    }

    public void setConnected(boolean connected) {
        this.connected.set(connected);
        if (connected) {
            updateLastActive();
        }
    }

    public void updateLastActive() {
        this.lastActiveTime = Instant.now();
    }

    public void incrementConnectionCount() {
        this.connectionCount.incrementAndGet();
        updateLastActive();
    }

    public int getConnectionCount() {
        return connectionCount.get();
    }

    // Sequence number handling
    public synchronized boolean updateSequenceNumber(short sequenceNumber) {
        if (sequenceNumber == INVALID_SEQUENCE) {
            return false;
        }

        // Handle rollover case
        if (lastSequenceNumber > SEQUENCE_ROLLOVER_THRESHOLD && sequenceNumber < 100) {
            lastSequenceNumber = sequenceNumber;
            updateLastActive();
            return true;
        }

        // Normal case
        if (sequenceNumber > lastSequenceNumber) {
            lastSequenceNumber = sequenceNumber;
            updateLastActive();
            return true;
        }

        return false;
    }

    // Serial number handling (GT06 specific)
    public synchronized void updateSerialNumber(short serialNumber) {
        this.lastSerialNumber = serialNumber;
        updateLastActive();
    }

    public synchronized boolean isDuplicateSerialNumber(short serialNumber) {
        return this.lastSerialNumber == serialNumber && !isExpired();
    }

    // Session expiration
    public boolean isExpired() {
        return isExpired(DEFAULT_TIMEOUT);
    }

    public boolean isExpired(Duration timeout) {
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

    @Override
    public String toString() {
        return "DeviceSession{" +
                "imei='" + imei.get() + '\'' +
                ", protocol='" + protocol + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", created=" + creationTime +
                ", lastActive=" + lastActiveTime +
                ", connected=" + connected +
                ", connectionCount=" + connectionCount +
                ", lastSerial=" + lastSerialNumber +
                ", lastSequence=" + lastSequenceNumber +
                '}';
    }

    // GT06-specific methods
    public synchronized boolean isNewerPacket(short sequenceNumber, short serialNumber) {
        // First check if serial number is different
        if (serialNumber != INVALID_SERIAL && serialNumber != lastSerialNumber) {
            return true;
        }

        // Then check sequence number
        return sequenceNumber > lastSequenceNumber;
    }

    public synchronized void updateActivity(short sequenceNumber, short serialNumber) {
        if (serialNumber != INVALID_SERIAL) {
            this.lastSerialNumber = serialNumber;
        }
        if (sequenceNumber != INVALID_SEQUENCE) {
            this.lastSequenceNumber = sequenceNumber;
        }
        this.lastActiveTime = Instant.now();
    }
}