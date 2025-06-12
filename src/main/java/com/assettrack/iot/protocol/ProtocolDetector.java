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

/**
 * ProtocolDetector classifies incoming raw byte arrays into known protocols.
 * It checks for Teltonika IMEI/AVL packets, GT06 frames, and TK103 ASCII messages.
 */
@Component
public class ProtocolDetector {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetector.class);
    private static final int MIN_DATA_LENGTH = 2;
    static final String VERSION = "1.0";

    public ProtocolDetectionResult detect(byte[] data) {
        if (data == null || data.length < MIN_DATA_LENGTH) {
            return ProtocolDetectionResult.failure("INVALID_DATA_LENGTH");
        }
        try {
            // 1) Teltonika
            TeltonikaMatcher teltonikaMatcher = new TeltonikaMatcher();
            if (teltonikaMatcher.matches(data)) {
                return ProtocolDetectionResult.success(
                        "TELTONIKA",
                        teltonikaMatcher.getPacketType(data),
                        VERSION
                );
            }

            // 2) GT06
            Gt06Matcher gt06Matcher = new Gt06Matcher();
            if (gt06Matcher.matches(data)) {
                return ProtocolDetectionResult.success(
                        "GT06",
                        gt06Matcher.getPacketType(data),
                        VERSION
                );
            }

            // 3) TK103
            Tk103Matcher tk103Matcher = new Tk103Matcher();
            if (tk103Matcher.matches(data)) {
                return ProtocolDetectionResult.success(
                        "TK103",
                        tk103Matcher.getPacketType(data),
                        VERSION
                );
            }

            // No known protocol
            return ProtocolDetectionResult.failure("UNKNOWN_PROTOCOL");
        } catch (Exception e) {
            logger.error("Error during protocol detection", e);
            return ProtocolDetectionResult.failure("DETECTION_ERROR");
        }
    }

    public static class ProtocolDetectionResult {
        private final boolean valid;
        private final String protocol;
        private final String packetType;
        private final String version;
        private final String error;
        private final Channel channel;

        public ProtocolDetectionResult(boolean valid, String protocol, String packetType) {
            this(valid, protocol, packetType, VERSION, null, null);
        }

        public ProtocolDetectionResult(
                boolean valid, String protocol, String packetType,
                String version, String error, Channel channel
        ) {
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

        public String getProtocol() { return protocol; }
        public String getPacketType() { return packetType; }
        public String getVersion() { return version; }
        public String getError() { return error; }
        public Channel getChannel() { return channel; }

        @Override
        public String toString() {
            return String.format(
                    "ProtocolDetectionResult[valid=%s, protocol=%s, packetType=%s, version=%s, error=%s]",
                    valid, protocol, packetType, version, error
            );
        }

        public boolean isValid() {
            return valid;
        }
    }

    interface ProtocolMatcher {
        boolean matches(byte[] data);
        String getPacketType(byte[] data);
    }

    static class Gt06Matcher implements ProtocolMatcher {
        @Override
        public boolean matches(byte[] data) {
            if (data.length < 5) return false;
            int length = data[2] & 0xFF;
            if (data.length < length + 5) return false;
            // terminator check
            return data[data.length - 2] == 0x0D && data[data.length - 1] == 0x0A;
        }

        @Override
        public String getPacketType(byte[] data) {
            if (data.length < 4) return "UNKNOWN";

            // Check for 0x7979 header (Configuration/Command Packet)
            if (data[0] == (byte)0x79 && data[1] == (byte)0x79) {
                return "CONFIGURATION_COMMAND_0x" + String.format("%02X", data[3]); // data[3] is likely the command type
            }

            switch (data[3]) {
                case 0x01: return "LOGIN";
                case 0x12: return "GPS_DATA";
                case 0x13: return "HEARTBEAT";
                case 0x16: return "ALARM";
                case 0x1A: return "STATUS";
                case (byte)0x80: return "GPRS_COMMAND";
                case (byte)0xA0: return "EXTENDED_DATA";
                default:
                    // handle extended‐GPS (0xA0) frames:
                    if (data[3] == (byte)0xA0) {
                        return "EXTENDED_DATA";
                    }
                    if ((data[3] & 0xF0) == 0x10) {
                        return "EXTENDED_DATA_0x" + String.format("%02X", data[3]);
                    }
                    return "UNKNOWN_GT06_" + String.format("%02X", data[3]);

            }
        }
    }

    static class Tk103Matcher implements ProtocolMatcher {
        @Override
        public boolean matches(byte[] data) {
            if (data.length < 4) return false;
            return data[0] == 0x78 && data[1] == 0x78
                    && data[data.length - 2] == 0x0D
                    && data[data.length - 1] == 0x0A;
        }

        @Override
        public String getPacketType(byte[] data) {
            String msg = new String(data, StandardCharsets.US_ASCII);
            if (msg.contains("A;") || msg.contains("a;")) return "LOGIN";
            return msg.contains(";") ? "DATA" : "UNKNOWN_TK103";
        }
    }

    static class TeltonikaMatcher implements ProtocolMatcher {
        @Override
        public boolean matches(byte[] data) {
            // IMEI packet: 17 bytes, starts with 0x00 0x0F
            if (data.length == 17 && data[0] == 0x00 && data[1] == 0x0F) {
                try {
                    String imei = new String(data, 2, 15, StandardCharsets.US_ASCII);
                    return imei.matches("^\\d{15}$");
                } catch (Exception e) {
                    logger.debug("Teltonika IMEI match failed due to invalid IMEI string: {}", Hex.encodeHexString(data), e);
                    return false;
                }
            }
            // AVL data packet: Starts with 4-byte preamble (0x00000000), then 4-byte length
            if (data.length >= 12) { // Minimum length for AVL data is 12 bytes (4 preamble + 4 length + 4 CRC)
                try {
                    ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                    if (buf.getInt(0) == 0x00000000) { // Preamble check
                        int avlLength = buf.getInt(4); // Length field at offset 4
                        // Total length = Preamble (4) + Length Field (4) + AVL Data (avlLength) + CRC (4)
                        return data.length == avlLength + 12;
                    }
                } catch (Exception e) {
                    logger.debug("Teltonika AVL match failed parsing packet: {}", Hex.encodeHexString(data), e);
                    return false;
                }
            }
            return false;
        }

        @Override
        public String getPacketType(byte[] data) {
            if (data.length == 17 && data[0] == 0x00 && data[1] == 0x0F) {
                try {
                    String imei = new String(data, 2, 15, StandardCharsets.US_ASCII);
                    if (imei.matches("^\\d{15}$")) {
                        return "IMEI";
                    }
                } catch (Exception e) {
                    // Fall through if IMEI string is invalid but length matches
                }
            }

            if (data.length >= 12) {
                try {
                    ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                    if (buf.getInt(0) == 0x00000000) { // Preamble
                        int avlLength = buf.getInt(4); // Length field
                        if (data.length == avlLength + 12) {
                            int codec = buf.get(8) & 0xFF; // Codec ID at offset 8
                            if (codec == 0x08) return "CODEC8";
                            if (codec == 0x8E) return "CODEC8_EXT";
                            if (codec == 0x10) return "CODEC16";
                            return "UNKNOWN_TELTONIKA_CODEC"; // Valid AVL packet structure, but unknown codec ID
                        }
                    }
                } catch (Exception e) {
                    // Fall through
                }
            }
            return "UNKNOWN_TELTONIKA_PACKET"; // If none of the above matches, it's a Teltonika-like packet, but type unknown.
        }
    }
}
