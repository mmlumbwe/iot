package com.assettrack.iot.session;

import java.net.SocketAddress;
import io.netty.channel.Channel;

public class DeviceSession {

    private final long deviceId;
    private final String uniqueId;
    private final Channel channel;
    private final SocketAddress remoteAddress;
    private long lastUpdate;

    public DeviceSession(long deviceId, String uniqueId, Channel channel, SocketAddress remoteAddress) {
        this.deviceId = deviceId;
        this.uniqueId = uniqueId;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.lastUpdate = System.currentTimeMillis();
    }

    // Getters
    public long getDeviceId() { return deviceId; }
    public String getUniqueId() { return uniqueId; }
    public Channel getChannel() { return channel; }
    public SocketAddress getRemoteAddress() { return remoteAddress; }
    public long getLastUpdate() { return lastUpdate; }

    public void update() {
        this.lastUpdate = System.currentTimeMillis();
    }
}