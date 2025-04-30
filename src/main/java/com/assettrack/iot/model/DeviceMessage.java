package com.assettrack.iot.model;

import java.util.HashMap;
import java.util.Map;

public class DeviceMessage {
    private String protocolType;  // Renamed from 'protocol' for consistency
    private String messageType;
    private String imei;
    private byte[] rawData;      // New field for raw byte data
    private Map<String, Object> parsedData;

    public static final String TYPE_LOGIN = "LOGIN";
    public static final String TYPE_LOCATION = "LOCATION";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String TYPE_ALARM = "ALARM";


    // No-arg constructor
    public DeviceMessage() {
        this.parsedData = new HashMap<>();
    }

    // All-args constructor
    public DeviceMessage(String protocolType, String messageType, String imei, byte[] rawData, Map<String, Object> parsedData) {
        this.protocolType = protocolType;
        this.messageType = messageType;
        this.imei = imei;
        this.rawData = rawData;
        this.parsedData = parsedData != null ? parsedData : new HashMap<>();
    }

    // Getters and Setters
    public String getProtocolType() {
        return protocolType;
    }

    public void setProtocolType(String protocolType) {
        this.protocolType = protocolType;
    }

    /**
     * @deprecated Use setProtocolType() instead
     */
    @Deprecated
    public void setProtocol(String protocol) {
        this.protocolType = protocol;
    }

    /**
     * @deprecated Use getProtocolType() instead
     */
    @Deprecated
    public String getProtocol() {
        return protocolType;
    }

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

    public Map<String, Object> getParsedData() {
        return parsedData;
    }

    public void setParsedData(Map<String, Object> parsedData) {
        this.parsedData = parsedData;
    }

    public void addParsedData(String key, Object value) {
        if (this.parsedData == null) {
            this.parsedData = new HashMap<>();
        }
        this.parsedData.put(key, value);
    }

    @Override
    public String toString() {
        return "DeviceMessage{" +
                "protocolType='" + protocolType + '\'' +
                ", messageType='" + messageType + '\'' +
                ", imei='" + imei + '\'' +
                ", rawData=" + (rawData != null ? "[" + rawData.length + " bytes]" : "null") +
                ", parsedData=" + parsedData +
                '}';
    }
}