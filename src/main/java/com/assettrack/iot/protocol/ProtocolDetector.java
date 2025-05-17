package com.assettrack.iot.protocol;

import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProtocolDetector {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetector.class);
    private static final Map<String, ProtocolMatcher> PROTOCOL_MATCHERS = new ConcurrentHashMap<>();
    private static final int MIN_DATA_LENGTH = 2;
    private final Map<String, ProtocolDetectionResult> detectionCache = new ConcurrentHashMap<>();

    static {
        registerProtocolMatcher("GT06", new Gt06Matcher());
        registerProtocolMatcher("TK103", new Tk103Matcher());
        registerProtocolMatcher("TELTONIKA", new TeltonikaMatcher());
    }

    public static void registerProtocolMatcher(String protocolName, ProtocolMatcher matcher) {
        if (protocolName != null && matcher != null) {
            PROTOCOL_MATCHERS.put(protocolName.toUpperCase(), matcher);
        }
    }

    public ProtocolDetectionResult detect(byte[] data) {
        logger.info("Received data ({} bytes): {}", data.length, Hex.encodeHexString(data));
        if (data == null || data.length < MIN_DATA_LENGTH) {
            return ProtocolDetectionResult.error(null, "INVALID", "Empty or short packet");
        }

        // First try cached result if available
        String dataKey = bytesToHex(data);
        ProtocolDetectionResult cachedResult = detectionCache.get(dataKey);
        if (cachedResult != null) {
            return cachedResult;
        }

        // Perform actual detection
        ProtocolDetectionResult result = performDetection(data);
        detectionCache.put(dataKey, result);
        return result;
    }

    private ProtocolDetectionResult performDetection(byte[] data) {
        try {
            for (Map.Entry<String, ProtocolMatcher> entry : PROTOCOL_MATCHERS.entrySet()) {
                try {
                    ProtocolMatcher matcher = entry.getValue();
                    if (matcher.matches(data)) {
                        String packetType = matcher.getPacketType(data);
                        String version = "1.0"; // Default version

                        // Special handling for Teltonika version detection
                        if ("TELTONIKA".equals(entry.getKey()) && "DATA".equals(packetType)) {
                            version = detectTeltonikaVersion(data);
                        }

                        return ProtocolDetectionResult.success(entry.getKey(), packetType, version);
                    }
                } catch (ProtocolDetectionException e) {
                    logger.debug("Protocol detection failed for {}: {}", entry.getKey(), e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Error during protocol detection", e);
        }

        return ProtocolDetectionResult.error("UNKNOWN", "UNKNOWN", "No matching protocol found");
    }

    private String detectTeltonikaVersion(byte[] data) {
        if (data.length > 8) {
            int codecId = data[8] & 0xFF;
            return TeltonikaConstants.CODECS.getOrDefault(codecId, "UNKNOWN_CODEC");
        }
        return "UNKNOWN_CODEC";
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
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

        // Getters
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

    public static class ProtocolDetectionException extends Exception {
        public ProtocolDetectionException(String message) {
            super(message);
        }
    }

    static class Gt06Matcher implements ProtocolMatcher {
        private static final int MIN_GT06_LENGTH = 12;
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
            if (!matches(data) || data.length < 4) {
                throw new ProtocolDetectionException("Not a GT06 packet");
            }
            switch (data[3] & 0xFF) {
                case 0x01: return "LOGIN";
                case 0x12: return "GPS";
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

    public static class TeltonikaConstants {
        public static final int IMEI_MIN_LENGTH = 15;
        public static final int IMEI_MAX_LENGTH = 17;
        public static final int MAX_PACKET_SIZE = 2048;

        public static final Map<Integer, String> CODECS = new LinkedHashMap<>();
        static {
            CODECS.put(0x08, "CODEC_8");
            CODECS.put(0x0C, "CODEC_12");
            CODECS.put(0x0E, "CODEC_13");
            CODECS.put(0x10, "CODEC_16");
        }
    }
}