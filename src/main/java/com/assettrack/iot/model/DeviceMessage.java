package com.assettrack.iot.model;

import io.netty.channel.socket.SocketChannel;
import java.net.Socket;
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
    // Protocol fields (both kept for backward compatibility)
    private String protocol;         // Raw protocol string (e.g., "GT06")
    private String protocolType;     // Normalized protocol type (e.g., "GT06")
    private String protocolVersion;  // Protocol version (e.g., "1.0")

    // Message metadata
    private String messageType;
    private String imei;
    private byte[] rawData;
    private String error;
    private Map<String, Object> parsedData;
    private LocalDateTime timestamp;
    private int signalStrength;
    private int batteryLevel;
    private SocketAddress remoteAddress;

    // Channel information
    private SocketChannel channel;  // Netty channel
    private Socket socket;         // Legacy socket (deprecated)

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
        this.parsedData = new HashMap<>();
    }

    // Protocol Accessors **********************************************

    /**
     * Gets the raw protocol string (e.g., exactly as received from device)
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * Sets both protocol and protocolType fields
     */
    public void setProtocol(String protocol) {
        this.protocol = protocol;
        this.protocolType = protocol; // Maintain backward compatibility
    }

    /**
     * @deprecated Use getProtocol() instead
     */
    @Deprecated
    public String getProtocolType() {
        return protocolType != null ? protocolType : protocol;
    }

    /**
     * @deprecated Use setProtocol() instead
     */
    @Deprecated
    public void setProtocolType(String protocolType) {
        this.protocolType = protocolType;
        if (this.protocol == null) {
            this.protocol = protocolType;
        }
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    // Message Content Accessors ***************************************

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getImei() {
        return imei;
    }

    public void setImei(String imei) {
        this.imei = imei;
    }

    public byte[] getRawData() {
        return rawData;
    }

    public void setRawData(byte[] rawData) {
        this.rawData = rawData;
    }

    // Error Handling *************************************************

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
        if (error != null && !error.isEmpty()) {
            this.messageType = TYPE_ERROR;
        }
    }

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }

    // Parsed Data Management *****************************************

    public Map<String, Object> getParsedData() {
        return new HashMap<>(parsedData);
    }

    public void setParsedData(Map<String, Object> parsedData) {
        this.parsedData = new HashMap<>(Objects.requireNonNull(parsedData));
    }

    public void addParsedData(String key, Object value) {
        this.parsedData.put(key, value);
    }

    // Device Status Accessors *****************************************

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

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

    // Channel Management *********************************************

    public SocketChannel getSocketChannel() {
        return channel;
    }

    public void setChannel(SocketChannel channel) {
        this.channel = channel;
        this.socket = null;
    }

    @Deprecated
    public Socket getSocket() {
        return socket;
    }

    @Deprecated
    public void setSocket(Socket socket) {
        this.socket = socket;
        this.channel = null;
    }

    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    // Response Handling **********************************************

    public byte[] getResponseData() {
        return responseData;
    }

    public void setResponseData(byte[] responseData) {
        this.responseData = responseData;
    }

    public boolean isResponseRequired() {
        return responseRequired;
    }

    public void setResponseRequired(boolean responseRequired) {
        this.responseRequired = responseRequired;
    }

    // Utility Methods ************************************************

    public Long getDeviceId() {
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
    public String toString() {
        return "DeviceMessage{" +
                "protocol='" + protocol + '\'' +
                ", protocolType='" + protocolType + '\'' +
                ", protocolVersion='" + protocolVersion + '\'' +
                ", messageType='" + messageType + '\'' +
                ", imei='" + imei + '\'' +
                ", timestamp=" + timestamp +
                ", error='" + error + '\'' +
                ", parsedData=" + parsedData +
                '}';
    }

    // Builder Pattern ************************************************

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String protocol;
        private String protocolType;
        private String protocolVersion;
        private String messageType;
        private String imei;
        private byte[] rawData;
        private Map<String, Object> parsedData = new HashMap<>();

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

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
            DeviceMessage message = new DeviceMessage();
            message.protocol = this.protocol;
            message.protocolType = this.protocolType != null ? this.protocolType : this.protocol;
            message.protocolVersion = this.protocolVersion;
            message.messageType = this.messageType;
            message.imei = this.imei;
            message.rawData = this.rawData;
            message.setParsedData(this.parsedData);
            return message;
        }
    }
}