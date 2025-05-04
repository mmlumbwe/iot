package com.assettrack.iot.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProtocolDetector {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetector.class);
    private static final Map<String, ProtocolMatcher> PROTOCOL_MATCHERS = new ConcurrentHashMap<>();
    private static final int MIN_DATA_LENGTH = 2;

    static {
        registerProtocolMatcher("GT06", new Gt06Matcher());
        registerProtocolMatcher("TK103", new Tk103Matcher());
        registerProtocolMatcher("TELTONIKA", new TeltonikaMatcher());
        registerProtocolMatcher("NEW_PROTOCOL", new NewProtocolMatcher()); // Add new matcher
    }

    public static void registerProtocolMatcher(String protocolName, ProtocolMatcher matcher) {
        if (protocolName != null && matcher != null) {
            PROTOCOL_MATCHERS.put(protocolName.toUpperCase(), matcher);
        }
    }

    public ProtocolDetectionResult detect(byte[] data) {
        if (data == null) {
            logger.warn("Null data received for protocol detection");
            return ProtocolDetectionResult.error("UNKNOWN", "INVALID", "Null data");
        }

        logger.debug("Received packet (hex): {}", bytesToHex(data));
        logger.debug("Starting protocol detection for packet length: {}", data.length);

        ProtocolDetectionResult teltonikaResult = checkTeltonikaProtocol(data);
        if (teltonikaResult.isValid()) {
            return teltonikaResult;
        }

        if (data.length < MIN_DATA_LENGTH) {
            return ProtocolDetectionResult.error("UNKNOWN", "INVALID", "Data too short");
        }

        for (Map.Entry<String, ProtocolMatcher> entry : PROTOCOL_MATCHERS.entrySet()) {
            try {
                if (entry.getValue().matches(data)) {
                    String protocol = entry.getKey();
                    String packetType = entry.getValue().getPacketType(data);
                    String version = getProtocolVersion(protocol, packetType, data);

                    logger.debug("Detected protocol: {} packet type: {} version: {}",
                            protocol, packetType, version);
                    return ProtocolDetectionResult.success(protocol, packetType, version);
                }
            } catch (Exception e) {
                logger.debug("Protocol matcher {} failed: {}", entry.getKey(), e.getMessage());
            }
        }

        return ProtocolDetectionResult.error("UNKNOWN", "INVALID", "No matching protocol found");
    }

    private ProtocolDetectionResult checkTeltonikaProtocol(byte[] data) {
        // Check IMEI packet first
        if (isTeltonikaImeiPacket(data)) {
            logger.debug("Detected Teltonika IMEI packet");
            return ProtocolDetectionResult.success("TELTONIKA", "IMEI", "1.0");
        }

        // Then check data packet
        if (isTeltonikaDataPacket(data)) {
            String version = detectTeltonikaVersion(data);
            logger.debug("Detected Teltonika data packet with codec: {}", version);
            return ProtocolDetectionResult.success("TELTONIKA", "DATA", version);
        }

        return ProtocolDetectionResult.error("TELTONIKA", "INVALID", "Not a Teltonika packet");
    }

    private boolean isTeltonikaImeiPacket(byte[] data) {
        if (data == null || data.length < 4) return false;

        int length = ((data[0] & 0xFF) << 8 | (data[1] & 0xFF));
        // Require complete packet (length + 2 bytes header)
        if (data.length != length + 2) {
            logger.debug("Incomplete IMEI packet (expected={}, actual={})",
                    length + 2, data.length);
            return false;
        }

        if (length < TeltonikaConstants.IMEI_MIN_LENGTH || length > TeltonikaConstants.IMEI_MAX_LENGTH) {
            return false;
        }

        try {
            String imei = new String(data, 2, length, StandardCharsets.US_ASCII);
            return imei.matches("^\\d{15,17}$");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTeltonikaDataPacket(byte[] data) {
        if (data == null || data.length < 12) return false;

        if (data[0] != 0 || data[1] != 0 || data[2] != 0 || data[3] != 0) {
            return false;
        }

        try {
            int dataLength = ByteBuffer.wrap(data, 4, 4)
                    .order(ByteOrder.BIG_ENDIAN).getInt();

            if (dataLength <= 0 || dataLength > TeltonikaConstants.MAX_PACKET_SIZE) {
                return false;
            }

            if (data.length > 8) {
                int codecId = data[8] & 0xFF;
                return TeltonikaConstants.CODECS.containsKey(codecId);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String detectTeltonikaVersion(byte[] data) {
        if (data.length > 8) {
            int codecId = data[8] & 0xFF;
            return TeltonikaConstants.CODECS.getOrDefault(codecId, "UNKNOWN_CODEC");
        }
        return "UNKNOWN_CODEC";
    }

    private String getProtocolVersion(String protocol, String packetType, byte[] data) {
        if ("TELTONIKA".equals(protocol) && "DATA".equals(packetType)) {
            return detectTeltonikaVersion(data);
        }
        return "1.0";
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    public static class ProtocolDetectionResult {
        private final String protocol;
        private final String packetType;
        private final String version;
        private final String error;
        private final Map<String, Object> metadata;

        private ProtocolDetectionResult(String protocol, String packetType, String version,
                                        String error, Map<String, Object> metadata) {
            this.protocol = protocol;
            this.packetType = packetType;
            this.version = version;
            this.error = error;
            this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        }

        public static ProtocolDetectionResult success(String protocol, String packetType, String version) {
            return new ProtocolDetectionResult(protocol, packetType, version, null, null);
        }

        public static ProtocolDetectionResult error(String protocol, String packetType, String error) {
            return new ProtocolDetectionResult(protocol, packetType, "UNKNOWN", error, null);
        }

        public ProtocolDetectionResult withMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public String getProtocol() { return protocol; }
        public String getPacketType() { return packetType; }
        public String getVersion() { return version; }
        public String getError() { return error; }
        public boolean isValid() { return error == null; }
        public Map<String, Object> getMetadata() { return new LinkedHashMap<>(metadata); }
    }

    public interface ProtocolMatcher {
        boolean matches(byte[] data) throws ProtocolDetectionException;
        String getPacketType(byte[] data) throws ProtocolDetectionException;
    }

    static class ProtocolDetectionException extends Exception {
        public ProtocolDetectionException(String message) {
            super(message);
        }
    }

    static class Gt06Matcher implements ProtocolMatcher {
        private static final int MIN_GT06_LENGTH = 5;
        private static final byte START_BYTE_1 = 0x78;
        private static final byte START_BYTE_2 = 0x78;
        private static final byte END_BYTE_1 = 0x0D;
        private static final byte END_BYTE_2 = 0x0A;

        @Override
        public boolean matches(byte[] data) throws ProtocolDetectionException {
            if (data == null || data.length < MIN_GT06_LENGTH) {
                return false;
            }
            return data[0] == START_BYTE_1 &&
                    data[1] == START_BYTE_2 &&
                    data[data.length-2] == END_BYTE_1 &&
                    data[data.length-1] == END_BYTE_2;
        }

        @Override
        public String getPacketType(byte[] data) throws ProtocolDetectionException {
            if (!matches(data)) {
                throw new ProtocolDetectionException("Not a GT06 packet");
            }
            switch (data[3] & 0xFF) {
                case 0x01: return "LOGIN";
                case 0x12: return "LOCATION";
                case 0x13: return "HEARTBEAT";
                case 0x16: return "ALARM";
                case 0x80: return "CONFIGURATION";
                default: return "DATA";
            }
        }
    }

    static class Tk103Matcher implements ProtocolMatcher {
        private static final int MIN_TK103_LENGTH = 6;
        private static final byte START_BYTE_1 = 0x23;
        private static final byte START_BYTE_2 = 0x23;

        @Override
        public boolean matches(byte[] data) throws ProtocolDetectionException {
            if (data == null || data.length < MIN_TK103_LENGTH) {
                return false;
            }
            return (data[0] == START_BYTE_1 && data[1] == START_BYTE_2) ||
                    new String(data, StandardCharsets.US_ASCII).matches("^\\d{15},.*");
        }

        @Override
        public String getPacketType(byte[] data) throws ProtocolDetectionException {
            if (!matches(data)) {
                throw new ProtocolDetectionException("Not a TK103 packet");
            }
            String message = new String(data, StandardCharsets.US_ASCII);
            if (message.startsWith("##")) {
                return message.contains("A;") ? "LOGIN" : "CONFIGURATION";
            }
            return message.contains("imei:") ? "IMEI" : "LOCATION";
        }
    }

    static class TeltonikaMatcher implements ProtocolMatcher {
        @Override
        public boolean matches(byte[] data) throws ProtocolDetectionException {
            if (data == null) return false;
            return isImeiPacket(data) || isDataPacket(data);
        }

        @Override
        public String getPacketType(byte[] data) throws ProtocolDetectionException {
            if (isImeiPacket(data)) return "IMEI";
            if (isDataPacket(data)) return "DATA";
            throw new ProtocolDetectionException("Not a Teltonika packet");
        }

        private boolean isImeiPacket(byte[] data) {
            if (data.length >= 2) {
                int length = ((data[0] & 0xFF) << 8 | (data[1] & 0xFF));
                if (data.length >= length + 2 && length >= 15 && length <= 17) {
                    try {
                        String imei = new String(data, 2, length, StandardCharsets.US_ASCII);
                        return imei.matches("^\\d{15,17}$");
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            return false;
        }

        private boolean isDataPacket(byte[] data) {
            if (data.length >= 8) {
                if (data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 0) {
                    try {
                        int dataLength = ByteBuffer.wrap(data, 4, 4)
                                .order(ByteOrder.BIG_ENDIAN).getInt();
                        return data.length >= dataLength + 8;
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            return false;
        }
    }

    public String detectProtocol(byte[] data) {
        return detect(data).getProtocol();
    }

    public static boolean isHeartbeatPacket(byte[] data) {
        return data.length == 4 &&
                data[0] == 0x00 &&
                data[1] == 0x00 &&
                data[2] == 0x00 &&
                data[3] == 0x00;
    }
}