package com.assettrack.iot.protocol;

import com.assettrack.iot.config.Checksum;
import io.netty.channel.Channel;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProtocolDetector {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetector.class);
    private static final Map<String, ProtocolMatcher> PROTOCOL_MATCHERS = new ConcurrentHashMap<>();
    static final int MIN_DATA_LENGTH = 2;

    // Protocol constants
    private static final byte PROTOCOL_HEADER_1 = 0x78;
    private static final byte PROTOCOL_HEADER_2 = 0x78;
    protected static final byte PROTOCOL_LOGIN = 0x01;

    private final Map<String, ProtocolDetectionResult> detectionCache = new ConcurrentHashMap<>();

    static {
        logger.info("ProtocolDetector: Registering protocol matchers...");
        registerProtocolMatcher("GT06", new Gt06Matcher());
        registerProtocolMatcher("TK103", new Tk103Matcher());
        registerProtocolMatcher("TELTONIKA", new TeltonikaMatcher());
        logger.info("ProtocolDetector: Finished registering protocol matchers. Total registered: {}", PROTOCOL_MATCHERS.size());
    }

    public static void registerProtocolMatcher(String protocolName, ProtocolMatcher matcher) {
        if (protocolName != null && matcher != null) {
            PROTOCOL_MATCHERS.put(protocolName.toUpperCase(), matcher);
        }
    }

    public static Object failure(String noDetection) {
        return null;
    }

    public ProtocolDetectionResult detect(byte[] data) {
        if (data == null || data.length < MIN_DATA_LENGTH) {
            return ProtocolDetectionResult.failure("INVALID_DATA_LENGTH");
        }

        String dataHex = Hex.encodeHexString(Arrays.copyOf(data, Math.min(data.length, 20)));
        if (detectionCache.containsKey(dataHex)) {
            return detectionCache.get(dataHex);
        }

        for (Map.Entry<String, ProtocolMatcher> entry : PROTOCOL_MATCHERS.entrySet()) {
            ProtocolMatcher matcher = entry.getValue();
            if (matcher.matches(data)) {
                ProtocolDetectionResult result = new ProtocolDetectionResult(
                        true,
                        entry.getKey(),
                        matcher.getPacketType(data)
                );
                detectionCache.put(dataHex, result);
                return result;
            }
        }
        return ProtocolDetectionResult.failure("UNKNOWN_PROTOCOL");
    }

    public static class ProtocolDetectionResult {
        private final boolean valid;
        private final String protocol;
        private final String packetType;
        private final String version;
        private final String error;
        private final Channel channel;

        public ProtocolDetectionResult(boolean valid, String protocol, String packetType) {
            this(valid, protocol, packetType, "1.0", null, null);
        }

        public ProtocolDetectionResult(boolean valid, String protocol, String packetType, Channel channel) {
            this(valid, protocol, packetType, "1.0", null, channel);
        }

        public ProtocolDetectionResult(boolean valid, String protocol, String packetType,
                                       String version, String error, Channel channel) {
            this.valid = valid;
            this.protocol = protocol;
            this.packetType = packetType;
            this.version = version;
            this.error = error;
            this.channel = channel;
        }

        public static ProtocolDetectionResult success(String protocol, String packetType, String version) {
            return new ProtocolDetectionResult(true, protocol, packetType, version, null, null);
        }

        public static ProtocolDetectionResult failure(String error) {
            return new ProtocolDetectionResult(false, "UNKNOWN", "ERROR", "0.0", error, null);
        }

        public boolean isDetected() {
            return valid;
        }

        public String getProtocol() {
            return protocol;
        }

        public String getPacketType() {
            return packetType;
        }

        public String getVersion() {
            return version;
        }

        public boolean isValid() {
            return valid;
        }

        public String getError() {
            return error;
        }

        public Channel getChannel() {
            return channel;
        }

        @Override
        public String toString() {
            return String.format(
                    "ProtocolDetectionResult[valid=%s, protocol=%s, packetType=%s, version=%s, error=%s]",
                    valid, protocol, packetType, version, error
            );
        }
    }

    interface ProtocolMatcher {
        boolean matches(byte[] data);
        String getPacketType(byte[] data);
    }

    static class Gt06Matcher implements ProtocolMatcher {
        @Override
        public boolean matches(byte[] data) {
            if (data == null || data.length < 4) return false;

            // Check header
            if (data[0] != PROTOCOL_HEADER_1 || data[1] != PROTOCOL_HEADER_2) {
                return false;
            }

            // Check length
            int declaredLength = data[2] & 0xFF;
            return data.length == (declaredLength + 4); // Header (2) + length (1) + data + CRC (1)
        }

        @Override
        public String getPacketType(byte[] data) {
            if (data.length < 4) return "UNKNOWN";
            switch (data[3]) {
                case 0x01: return "LOGIN";
                case 0x12: return "GPS_DATA";
                case 0x13: return "HEARTBEAT";
                case 0x16: return "ALARM";
                default: return "UNKNOWN_GT06";
            }
        }
    }

    static class Tk103Matcher implements ProtocolMatcher {
        @Override
        public boolean matches(byte[] data) {
            if (data == null || data.length < 4) return false;

            // Check header and terminator
            return (data[0] == PROTOCOL_HEADER_1 && data[1] == PROTOCOL_HEADER_2) &&
                    (data[data.length-2] == 0x0D && data[data.length-1] == 0x0A);
        }

        @Override
        public String getPacketType(byte[] data) {
            if (data.length < 4) return "UNKNOWN";
            String message = new String(data, StandardCharsets.US_ASCII);
            if (message.contains("A;") || message.contains("a;")) {
                return "LOGIN";
            }
            return message.contains(";") ? "DATA" : "UNKNOWN_TK103";
        }
    }

    static class TeltonikaMatcher implements ProtocolMatcher {
        @Override
        public boolean matches(byte[] data) {
            if (data == null) return false;

            // IMEI packet
            if (data.length == 17 && data[0] == 0x00 && data[1] == 0x0F) {
                try {
                    String imei = new String(data, 2, 15, StandardCharsets.US_ASCII);
                    return imei.matches("^\\d{15}$");
                } catch (Exception e) {
                    return false;
                }
            }

            // AVL data packet
            if (data.length >= 12) {
                try {
                    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                    int avlLength = buffer.getInt(4);
                    return data.length == (avlLength + 12);
                } catch (Exception e) {
                    return false;
                }
            }

            return false;
        }

        @Override
        public String getPacketType(byte[] data) {
            if (data == null) return "UNKNOWN";

            // IMEI packet
            if (data.length == 17 && data[0] == 0x00 && data[1] == 0x0F) {
                return "IMEI";
            }

            // AVL data packet
            if (data.length >= 12) {
                try {
                    int codecId = data[8] & 0xFF;
                    return "AVL_DATA_CODEC_" + codecId;
                } catch (Exception e) {
                    return "UNKNOWN_AVL";
                }
            }

            return "UNKNOWN_TELTONIKA";
        }
    }

    public static class TeltonikaConstants {
        public static final int IMEI_MIN_LENGTH = 15;
        public static final Map<Integer, String> CODECS = Map.of(
                0x08, "CODEC_8",
                0x0C, "CODEC_7",
                0x10, "CODEC_16",
                0x8E, "CODEC_8_EXT"
        );
    }
}