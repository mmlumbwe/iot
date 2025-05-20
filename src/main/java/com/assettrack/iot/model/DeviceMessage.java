package com.assettrack.iot.model;

import io.netty.channel.socket.SocketChannel;
import java.net.SocketAddress;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a message from an IoT device with protocol information,
 * message type, device identification, and parsed data.
 * Thread-safe implementation for concurrent access.
 */
public class DeviceMessage {
    // Protocol information
    private String protocol;
    private String protocolType;
    private String protocolVersion;

    // Message metadata
    private String messageType;
    private String imei;
    private byte[] rawData;
    private String error;
    private final Map<String, Object> parsedData;
    private LocalDateTime timestamp;
    private int signalStrength;
    private int batteryLevel;
    private SocketAddress remoteAddress;

    // Channel information
    private SocketChannel channel;
    private volatile boolean duplicate = false;

    // Response handling
    private byte[] responseData;
    private boolean responseRequired;

    // Standard message types
    public static final String TYPE_LOGIN = "LOGIN";
    public static final String TYPE_LOCATION = "LOCATION";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String TYPE_ALARM = "ALARM";
    public static final String TYPE_ERROR = "ERROR";
    public static final String TYPE_CONFIGURATION = "CONFIGURATION";

    public DeviceMessage() {
        this.parsedData = Collections.synchronizedMap(new HashMap<>());
    }

    // Protocol Accessors
    public synchronized String getProtocol() {
        return protocol;
    }

    public synchronized void setProtocol(String protocol) {
        this.protocol = protocol;
        this.protocolType = protocol; // Maintain backward compatibility
    }

    public synchronized String getProtocolType() {
        return protocolType != null ? protocolType : protocol;
    }

    public synchronized void setProtocolType(String protocolType) {
        this.protocolType = protocolType;
        if (this.protocol == null) {
            this.protocol = protocolType;
        }
    }

    public synchronized String getProtocolVersion() {
        return protocolVersion;
    }

    public synchronized void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    // Message Content Accessors
    public synchronized String getMessageType() {
        return messageType;
    }

    public synchronized void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public synchronized String getImei() {
        return imei;
    }

    public synchronized void setImei(String imei) {
        this.imei = imei;
    }

    public synchronized byte[] getRawData() {
        return rawData != null ? rawData.clone() : null;
    }

    public synchronized void setRawData(byte[] rawData) {
        this.rawData = rawData != null ? rawData.clone() : null;
    }

    // Error Handling
    public synchronized String getError() {
        return error;
    }

    public synchronized void setError(String error) {
        this.error = error;
        if (error != null && !error.isEmpty()) {
            this.messageType = TYPE_ERROR;
        }
    }

    public synchronized boolean hasError() {
        return error != null && !error.isEmpty();
    }

    // Parsed Data Management
    public synchronized Map<String, Object> getParsedData() {
        return new HashMap<>(parsedData);
    }

    public synchronized void setParsedData(Map<String, Object> parsedData) {
        this.parsedData.clear();
        if (parsedData != null) {
            this.parsedData.putAll(parsedData);
        }
    }

    public synchronized void addParsedData(String key, Object value) {
        this.parsedData.put(key, value);
    }

    // Device Status Accessors
    public synchronized LocalDateTime getTimestamp() {
        return timestamp;
    }

    public synchronized void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public synchronized int getSignalStrength() {
        return signalStrength;
    }

    public synchronized void setSignalStrength(int signalStrength) {
        this.signalStrength = signalStrength;
    }

    public synchronized int getBatteryLevel() {
        return batteryLevel;
    }

    public synchronized void setBatteryLevel(int batteryLevel) {
        this.batteryLevel = batteryLevel;
    }

    // Channel Management
    public synchronized SocketChannel getSocketChannel() {
        return channel;
    }

    public synchronized void setChannel(SocketChannel channel) {
        this.channel = channel;
    }

    public synchronized SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public synchronized void setRemoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    // Response Handling
    public synchronized byte[] getResponseData() {
        return responseData != null ? responseData.clone() : null;
    }

    public synchronized void setResponseData(byte[] responseData) {
        this.responseData = responseData != null ? responseData.clone() : null;
    }

    public synchronized boolean isResponseRequired() {
        return responseRequired;
    }

    public synchronized void setResponseRequired(boolean responseRequired) {
        this.responseRequired = responseRequired;
    }

    // Duplicate Handling
    public synchronized boolean isDuplicate() {
        return duplicate;
    }

    public synchronized void setDuplicate(boolean duplicate) {
        this.duplicate = duplicate;
    }

    // Utility Methods
    public synchronized Long getDeviceId() {
        Object deviceIdObj = parsedData.get("deviceId");
        if (deviceIdObj instanceof Long) return (Long) deviceIdObj;
        if (deviceIdObj instanceof Integer) return ((Integer) deviceIdObj).longValue();
        if (deviceIdObj instanceof String) {
            try {
                return Long.parseLong((String) deviceIdObj);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public synchronized String toString() {
        return "DeviceMessage{" +
                "protocol='" + protocol + '\'' +
                ", messageType='" + messageType + '\'' +
                ", imei='" + imei + '\'' +
                ", timestamp=" + timestamp +
                ", duplicate=" + duplicate +
                ", parsedData=" + parsedData.keySet() +
                '}';
    }

    // Builder Pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final DeviceMessage message = new DeviceMessage();

        public Builder protocol(String protocol) {
            message.setProtocol(protocol);
            return this;
        }

        public Builder protocolType(String protocolType) {
            message.setProtocolType(protocolType);
            return this;
        }

        public Builder protocolVersion(String protocolVersion) {
            message.setProtocolVersion(protocolVersion);
            return this;
        }

        public Builder messageType(String messageType) {
            message.setMessageType(messageType);
            return this;
        }

        public Builder imei(String imei) {
            message.setImei(imei);
            return this;
        }

        public Builder rawData(byte[] rawData) {
            message.setRawData(rawData);
            return this;
        }

        public Builder addParsedData(String key, Object value) {
            message.addParsedData(key, value);
            return this;
        }

        public Builder duplicate(boolean duplicate) {
            message.setDuplicate(duplicate);
            return this;
        }

        public DeviceMessage build() {
            return message;
        }
    }
}