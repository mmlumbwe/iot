package com.assettrack.iot.session;

import io.netty.channel.Channel;
import java.net.SocketAddress;

public class DeviceSession {
    private final long deviceId;
    private final String uniqueId; // IMEI
    private final String protocolType;
    private Channel channel;  // Changed from final to allow updates
    private SocketAddress remoteAddress;  // Changed from final to allow updates
    private volatile long lastUpdate = System.currentTimeMillis();
    private volatile boolean connected = false;
    private volatile short lastSerialNumber = 0;
    private volatile short lastSequenceNumber = 0;
    private volatile boolean awaitingLoginResponse = false;
    private volatile long loginRequestTime = 0;
    private volatile boolean duplicate = false;  // Added duplicate flag

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
        this.connected = (channel != null && channel.isActive());
    }

    // Getters
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
        return connected && (channel != null && channel.isActive());
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    // Setters
    public synchronized void setDuplicate(boolean duplicate) {
        this.duplicate = duplicate;
    }

    public synchronized void setConnected(boolean connected) {
        this.connected = connected;
        if (connected) {
            updateLastActivity();
        }
    }

    public synchronized void updateLastActivity() {
        this.lastUpdate = System.currentTimeMillis();
    }

    public boolean isExpired() {
        return !isConnected() || (System.currentTimeMillis() - lastUpdate) > 300000; // 5 minute timeout
    }

    // GT06-specific methods
    public boolean shouldReconnect(short serialNumber) {
        return !isConnected() || serialNumber > lastSerialNumber;
    }

    public synchronized void updateSerialNumber(short serialNumber) {
        this.lastSerialNumber = serialNumber;
        updateLastActivity();
    }

    public synchronized boolean isDuplicateSerialNumber(short serialNumber) {
        if (serialNumber <= lastSerialNumber) {
            // If we're getting older serial numbers, the device may have reset
            return (lastSerialNumber - serialNumber) < 1000; // Allow for some rollover
        }
        return false;
    }

    // Sequence number management
    public synchronized boolean updateSequenceNumber(short sequenceNumber) {
        if (sequenceNumber > lastSequenceNumber ||
                (sequenceNumber < 0 && lastSequenceNumber > 0)) { // Handle rollover
            lastSequenceNumber = sequenceNumber;
            updateLastActivity();
            return true;
        }
        return false;
    }

    public synchronized boolean isAwaitingLoginResponse() {
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

    public synchronized void resetLoginState() {
        this.awaitingLoginResponse = false;
        this.loginRequestTime = 0;
    }

    public synchronized void setAwaitingLoginResponse(boolean awaiting) {
        this.awaitingLoginResponse = awaiting;
        this.loginRequestTime = awaiting ? System.currentTimeMillis() : 0;
    }

    public synchronized void setChannel(Channel channel) {
        this.channel = channel;
        this.connected = (channel != null && channel.isActive());
        if (this.connected) {
            updateLastActivity();
        }
    }

    public synchronized void setRemoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    // Helper methods
    public boolean isActive() {
        return isConnected() && !isExpired();
    }

    public boolean isSameDevice(DeviceSession other) {
        if (other == null) return false;
        return this.uniqueId.equals(other.uniqueId);
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
                ", duplicate=" + duplicate +
                '}';
    }
}