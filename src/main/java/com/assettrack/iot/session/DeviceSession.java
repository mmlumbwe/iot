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

    public DeviceSession(long deviceId, String uniqueId, String protocolType,
                         Channel channel, SocketAddress remoteAddress) {
        this.deviceId = deviceId;
        this.uniqueId = uniqueId;
        this.protocolType = protocolType;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
    }

    // Getters for all required fields
    public String getSessionId() {
        return this.uniqueId; // Using IMEI as session ID
    }

    public String getImei() {
        return this.uniqueId; // Alias for getUniqueId()
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
}