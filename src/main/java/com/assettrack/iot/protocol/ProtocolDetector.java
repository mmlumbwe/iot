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

        // Corrected implementation for isSuccess()
        public boolean isSuccess() {
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
            int length = data[2] & 0xFF; // Length field at offset 2 for GT06
            if (data.length < length + 5) return false; // Total length: 1 (start) + 1 (protocol) + length + 2 (CRC) + 2 (end)
            // terminator check: 0x0D 0x0A
            return data[data.length - 2] == 0x0D && data[data.length - 1] == 0x0A;
        }

        @Override
        public String getPacketType(byte[] data) {
            if (data.length < 4) return "UNKNOWN";

            // Check for 0x7979 header (Configuration/Command Packet)
            if (data[0] == (byte)0x79 && data[1] == (byte)0x79) {
                // data[3] is typically the command type
                return "CONFIGURATION_COMMAND_0x" + String.format("%02X", data[3]);
            }

            switch (data[3]) { // Protocol number at offset 3
                case 0x01: return "LOGIN";
                case 0x12: return "GPS_DATA";
                case 0x13: return "HEARTBEAT";
                case 0x16: return "ALARM";
                case 0x1A: return "STATUS";
                case (byte)0x80: return "GPRS_COMMAND";
                case (byte)0xA0: return "EXTENDED_DATA"; // Standard extended data
                default:
                    // handle extended‐GPS (0xA0) frames and other less common types
                    if ((data[3] & 0xF0) == 0x10) { // Check for other 0x1X types
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
            // TK103 packets typically start with 0x78 0x78 and end with 0x0D 0x0A
            return data[0] == 0x78 && data[1] == 0x78
                    && data[data.length - 2] == 0x0D
                    && data[data.length - 1] == 0x0A;
        }

        @Override
        public String getPacketType(byte[] data) {
            // TK103 packet types are often identified by ASCII content
            String msg = new String(data, StandardCharsets.US_ASCII);
            if (msg.contains("A;") || msg.contains("a;")) return "LOGIN"; // Login packet often contains 'A;' or 'a;'
            return msg.contains(";") ? "DATA" : "UNKNOWN_TK103"; // Other packets usually contain ';'
        }
    }

    static class TeltonikaMatcher implements ProtocolMatcher {
        // Constants for Teltonika IMEI packet structure
        private static final int IMEI_PACKET_LENGTH = 17;
        private static final byte IMEI_HEADER_BYTE1 = 0x00;
        private static final byte IMEI_HEADER_BYTE2 = 0x0F;
        private static final int IMEI_START_OFFSET = 2;
        private static final int IMEI_STRING_LENGTH = 15;

        // Constants for Teltonika AVL data packet structure
        private static final int AVL_PREAMBLE_OFFSET = 0;
        private static final int AVL_PREAMBLE_VALUE = 0x00000000; // 4-byte preamble
        private static final int AVL_LENGTH_FIELD_OFFSET = 4;    // Length field starts at offset 4
        private static final int AVL_CODEC_ID_OFFSET = 8;        // Codec ID starts at offset 8
        private static final int AVL_CRC_LENGTH = 4;             // 4-byte CRC at the end
        // Minimum total length for an AVL data packet (Preamble + Length Field + CRC)
        private static final int AVL_MIN_TOTAL_LENGTH_WITH_METADATA = 4 + 4 + 4; // = 12 bytes

        @Override
        public boolean matches(byte[] data) {
            // IMEI packet: 17 bytes, starts with 0x00 0x0F
            if (data.length == IMEI_PACKET_LENGTH && data[0] == IMEI_HEADER_BYTE1 && data[1] == IMEI_HEADER_BYTE2) {
                try {
                    String imei = new String(data, IMEI_START_OFFSET, IMEI_STRING_LENGTH, StandardCharsets.US_ASCII);
                    return imei.matches("^\\d{15}$"); // Validate IMEI is 15 digits
                } catch (Exception e) {
                    logger.debug("Teltonika IMEI match failed due to invalid IMEI string: {}", Hex.encodeHexString(data), e);
                    return false;
                }
            }
            // AVL data packet: Starts with 4-byte preamble (0x00000000), then 4-byte length
            if (data.length >= AVL_MIN_TOTAL_LENGTH_WITH_METADATA) {
                try {
                    ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                    if (buf.getInt(AVL_PREAMBLE_OFFSET) == AVL_PREAMBLE_VALUE) { // Preamble check
                        int avlLength = buf.getInt(AVL_LENGTH_FIELD_OFFSET); // Length field at offset 4
                        // Total packet length = Preamble + Length Field + AVL Data Length + CRC
                        return data.length == avlLength + AVL_MIN_TOTAL_LENGTH_WITH_METADATA;
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
            // First check for IMEI packet type
            if (data.length == IMEI_PACKET_LENGTH && data[0] == IMEI_HEADER_BYTE1 && data[1] == IMEI_HEADER_BYTE2) {
                try {
                    String imei = new String(data, IMEI_START_OFFSET, IMEI_STRING_LENGTH, StandardCharsets.US_ASCII);
                    if (imei.matches("^\\d{15}$")) {
                        return "IMEI";
                    }
                } catch (Exception e) {
                    // Fall through if IMEI string is invalid but length matches
                }
            }

            // Then check for AVL data packet type
            if (data.length >= AVL_MIN_TOTAL_LENGTH_WITH_METADATA) {
                try {
                    ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                    if (buf.getInt(AVL_PREAMBLE_OFFSET) == AVL_PREAMBLE_VALUE) { // Preamble check
                        int avlLength = buf.getInt(AVL_LENGTH_FIELD_OFFSET); // Length field
                        if (data.length == avlLength + AVL_MIN_TOTAL_LENGTH_WITH_METADATA) {
                            int codec = buf.get(AVL_CODEC_ID_OFFSET) & 0xFF; // Codec ID at offset 8
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