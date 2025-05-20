package com.assettrack.iot.session;

import io.netty.channel.Channel;
import java.net.SocketAddress;

public class DeviceSession {
    private final long deviceId;
    private final String uniqueId; // IMEI
    private final String protocolType;
    private final Channel channel;
    private final SocketAddress remoteAddress;
    private volatile long lastUpdate = System.currentTimeMillis();
    private volatile boolean connected = false;
    private volatile short lastSerialNumber = 0;
    private volatile short lastSequenceNumber = 0;
    private volatile boolean awaitingLoginResponse = false;
    private volatile long loginRequestTime = 0;

    public DeviceSession(long deviceId, String uniqueId, String protocolType,
                         Channel channel, SocketAddress remoteAddress) {
        if (uniqueId == null || uniqueId.trim().isEmpty()) {
            throw new IllegalArgumentException("Unique ID (IMEI) cannot be null or empty");
        }
        this.deviceId = deviceId;
        this.uniqueId = uniqueId;
        this.protocolType = protocolType;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
    }

    // Getters for all required fields
    public String getSessionId() {
        return this.uniqueId;
    }

    public String getImei() {
        return this.uniqueId;
    }

    public String getProtocol() {
        return this.protocolType;
    }

    public long getDeviceId() {
        return deviceId;
    }

    public String getUniqueId() {
        return uniqueId;
    }

    public String getProtocolType() {
        return protocolType;
    }

    public Channel getChannel() {
        return channel;
    }

    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public long getLastUpdate() {
        return lastUpdate;
    }

    public boolean isConnected() {
        return connected;
    }

    // Session state methods
    public void setConnected(boolean connected) {
        this.connected = connected;
        if (connected) {
            updateLastActivity();
        }
    }

    public void updateLastActivity() {
        this.lastUpdate = System.currentTimeMillis();
    }

    public boolean isExpired() {
        return !connected || (System.currentTimeMillis() - lastUpdate) > 300000; // 5 minute timeout
    }

    // GT06-specific methods
    public boolean shouldReconnect(short serialNumber) {
        return !connected || serialNumber > lastSerialNumber;
    }

    public void updateSerialNumber(short serialNumber) {
        this.lastSerialNumber = serialNumber;
        updateLastActivity();
    }

    public boolean isDuplicateSerialNumber(short serialNumber) {
        return connected && serialNumber <= lastSerialNumber;
    }

    // Sequence number management
    public boolean updateSequenceNumber(short sequenceNumber) {
        if (sequenceNumber > lastSequenceNumber ||
                (sequenceNumber < 0 && lastSequenceNumber > 0)) { // Handle rollover
            lastSequenceNumber = sequenceNumber;
            updateLastActivity();
            return true;
        }
        return false;
    }

    public boolean isAwaitingLoginResponse() {
        if (!awaitingLoginResponse) {
            return false;
        }
        // Check if we've been waiting too long (30 seconds timeout)
        if (System.currentTimeMillis() - loginRequestTime > 30000) {
            awaitingLoginResponse = false;
            return false;
        }
        return true;
    }

    public void resetLoginState() {
        this.awaitingLoginResponse = false;
        this.loginRequestTime = 0;
    }

    public void setAwaitingLoginResponse(boolean awaiting) {
        this.awaitingLoginResponse = awaiting;
        this.loginRequestTime = awaiting ? System.currentTimeMillis() : 0;
    }

    @Override
    public String toString() {
        return "DeviceSession{" +
                "deviceId=" + deviceId +
                ", uniqueId='" + uniqueId + '\'' +
                ", protocolType='" + protocolType + '\'' +
                ", connected=" + connected +
                ", lastUpdate=" + lastUpdate +
                ", remoteAddress=" + remoteAddress +
                '}';
    }

    // Additional helper methods
    public boolean isActive() {
        return connected && !isExpired();
    }

    public boolean isSameDevice(DeviceSession other) {
        if (other == null) return false;
        return this.uniqueId.equals(other.uniqueId);
    }
}