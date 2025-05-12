package com.assettrack.iot.model;

import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.net.SocketAddress;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a message from an IoT device containing protocol information,
 * message type, device identification, and parsed data.
 */
public class DeviceMessage {
    private String protocolType;
    private String protocolVersion;
    private String messageType;
    private String imei;
    private byte[] rawData;
    private String error;
    private Map<String, Object> parsedData = new HashMap<>();;
    private LocalDateTime timestamp;
    private int signalStrength;
    private int batteryLevel;
    private SocketAddress remoteAddress;

    private SocketChannel channel;  // Using SocketChannel for NIO support
    private Socket socket;         // Traditional Socket backup

    // Standard message types
    public static final String TYPE_LOGIN = "LOGIN";
    public static final String TYPE_LOCATION = "LOCATION";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String TYPE_ALARM = "ALARM";
    public static final String TYPE_ERROR = "ERROR";
    public static final String TYPE_CONFIGURATION = "CONFIGURATION";

    /**
     * Constructs an empty DeviceMessage with initialized parsedData map.
     */
    public DeviceMessage() {
        this.parsedData = new HashMap<>();
    }

    /**
     * Constructs a DeviceMessage with specified parameters.
     */
    public DeviceMessage(String protocolType, String protocolVersion, String messageType,
                         String imei, byte[] rawData, Map<String, Object> parsedData) {
        this.protocolType = protocolType;
        this.protocolVersion = protocolVersion;
        this.messageType = messageType;
        this.imei = imei;
        this.rawData = rawData;
        this.parsedData = parsedData != null ? new HashMap<>(parsedData) : new HashMap<>();
    }

    // Protocol Type Accessors
    public String getProtocolType() {
        return protocolType;
    }

    public void setProtocolType(String protocolType) {
        this.protocolType = protocolType;
    }

    // Protocol Version Accessors
    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    // Message Type Accessors
    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    // IMEI Accessors
    public String getImei() {
        return imei;
    }

    public void setImei(String imei) {
        this.imei = imei;
    }

    // Raw Data Accessors
    public byte[] getRawData() {
        return rawData;
    }

    public void setRawData(byte[] rawData) {
        this.rawData = rawData;
    }

    // Error Handling
    public boolean hasError() {
        return error != null && !error.isEmpty();
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
        if (error != null && !error.isEmpty()) {
            this.messageType = TYPE_ERROR;
        }
    }

    // Parsed Data Management
    public Map<String, Object> getParsedData() {
        return new HashMap<>(parsedData);
    }

    public void setParsedData(Map<String, Object> parsedData) {
        this.parsedData = new HashMap<>(Objects.requireNonNull(parsedData));
    }

    public void addParsedData(String key, Object value) {
        if (this.parsedData == null) {
            this.parsedData = new HashMap<>();
        }
        this.parsedData.put(key, value);
    }

    // Timestamp Accessors
    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    // Device Status Accessors
    public int getSignalStrength() {
        return signalStrength;
    }

    public void setSignalStrength(int signalStrength) {
        this.signalStrength = signalStrength;
    }

    public int getBatteryLevel() {
        return batteryLevel;
    }

    public void setBatteryLevel(int batteryLevel) {
        this.batteryLevel = batteryLevel;
    }

    // Deprecated methods for backward compatibility
    @Deprecated
    public void setProtocol(String protocol) {
        this.protocolType = protocol;
    }

    @Deprecated
    public String getProtocol() {
        return protocolType;
    }


    @Override
    public String toString() {
        return "DeviceMessage{" +
                "protocolType='" + protocolType + '\'' +
                ", protocolVersion='" + protocolVersion + '\'' +
                ", messageType='" + messageType + '\'' +
                ", imei='" + imei + '\'' +
                ", rawData=" + (rawData != null ? "[" + rawData.length + " bytes]" : "null") +
                ", timestamp=" + timestamp +
                ", signalStrength=" + signalStrength +
                ", batteryLevel=" + batteryLevel +
                ", error='" + error + '\'' +
                ", parsedData=" + parsedData +
                '}';
    }

    // Builder pattern for fluent construction
    public static Builder builder() {
        return new Builder();
    }

    public void setResponse(byte[] response) {
    }

    public void setRemoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public SocketAddress getRemoteAddress() {
        return this.remoteAddress;
    }

    /**
     * Gets the SocketChannel for NIO communication.
     * Falls back to traditional Socket if NIO isn't available.
     */
    public Socket getChannel() {
        if (this.channel != null && this.channel.isOpen()) {
            return this.channel.socket();  // Extract Socket from SocketChannel
        } else if (this.socket != null && !this.socket.isClosed()) {
            return this.socket;
        }
        return null;
    }

    /**
     * Gets the raw SocketChannel (NIO).
     */
    public SocketChannel getSocketChannel() {
        return this.channel;
    }

    /**
     * Sets the SocketChannel (NIO preferred).
     */
    public void setChannel(SocketChannel channel) {
        this.channel = channel;
        this.socket = null;  // Clear legacy socket
    }

    /**
     * Sets a traditional Socket (fallback).
     */
    public void setSocket(Socket socket) {
        this.socket = socket;
        this.channel = null;  // Clear NIO channel
    }

    public static class Builder {
        private String protocolType;
        private String protocolVersion;
        private String messageType;
        private String imei;
        private byte[] rawData;
        private Map<String, Object> parsedData = new HashMap<>();

        public Builder protocolType(String protocolType) {
            this.protocolType = protocolType;
            return this;
        }

        public Builder protocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
            return this;
        }

        public Builder messageType(String messageType) {
            this.messageType = messageType;
            return this;
        }

        public Builder imei(String imei) {
            this.imei = imei;
            return this;
        }

        public Builder rawData(byte[] rawData) {
            this.rawData = rawData;
            return this;
        }

        public Builder addParsedData(String key, Object value) {
            this.parsedData.put(key, value);
            return this;
        }

        public DeviceMessage build() {
            return new DeviceMessage(protocolType, protocolVersion, messageType,
                    imei, rawData, parsedData);
        }
    }
}