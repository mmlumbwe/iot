package com.assettrack.iot.protocol;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@Component
public class ProtocolDetector {
    private static final Map<String, ProtocolMatcher> PROTOCOL_MATCHERS = Map.of(
            "GT06", new Gt06Matcher(),
            "TK103", new Tk103Matcher(),
            "TELTONIKA", new TeltonikaMatcher()
    );

    public String detectProtocol(byte[] data) {
        if (data == null || data.length < 2) {
            return "UNKNOWN";
        }

        // Check for TK103 login (##...)
        if (data[0] == 0x23 && data[1] == 0x23) {
            return "TK103";
        }

        // Check for TK103 location (starts with IMEI)
        String message = new String(data, StandardCharsets.US_ASCII);
        if (message.matches("^\\d{15},.*")) {
            return "TK103";
        }

        // Check for GT06 (0x78 0x78)
        if (data[0] == 0x78 && data[1] == 0x78) {
            return "GT06";
        }

        // Check for Teltonika
        if (data.length >= 4 && data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x00) {
            return "TELTONIKA";
        }

        return "UNKNOWN";
    }

    public String getPacketType(byte[] data) {
        String protocol = detectProtocol(data);

        if ("TK103".equals(protocol)) {
            String message = new String(data, StandardCharsets.US_ASCII);
            if (message.startsWith("##")) {
                return "LOGIN";
            } else {
                return "LOCATION";
            }
        }

        if ("GT06".equals(protocol)) {
            if (data.length > 2) {
                switch (data[2]) {
                    case 0x01: return "LOGIN";
                    case 0x12: return "LOCATION";
                    case 0x13: return "STATUS";
                    default: return "UNKNOWN";
                }
            }
        }

        return "UNKNOWN";
    }

    public interface ProtocolMatcher {
        boolean matches(byte[] data);
        String getPacketType(byte[] data);
    }

    static class Gt06Matcher implements ProtocolMatcher {
        @Override
        public boolean matches(byte[] data) {
            if (data == null || data.length < 5) return false;

            // Check header bytes (0x78 0x78)
            if (data[0] != 0x78 || data[1] != 0x78) return false;

            int declaredLength = data[2] & 0xFF;
            int protocolNumber = data[3] & 0xFF;

            // Verify total length matches declared length + 5
            if (data.length != declaredLength + 5) {
                return false;
            }

            // Verify termination bytes (0x0D 0x0A)
            if (data[data.length-2] != 0x0D || data[data.length-1] != 0x0A) {
                return false;
            }

            // Protocol-specific validation
            switch (protocolNumber) {
                case 0x01:  // Login
                    return data.length >= 18 && (data.length - 5) == declaredLength;
                case 0x12:  // Location
                    return data.length >= 35 && (data.length - 5) == declaredLength;
                case 0x13:  // Status
                    return data.length >= 20 && (data.length - 5) == declaredLength;
                default:
                    return (data.length - 5) == declaredLength;
            }
        }

        @Override
        public String getPacketType(byte[] data) {
            if (!matches(data)) return "UNKNOWN";

            switch (data[3] & 0xFF) {
                case 0x01: return "LOGIN";
                case 0x12: return "LOCATION";
                case 0x13: return "STATUS";
                case 0x16: return "ALARM";
                case 0x1A: return "HEARTBEAT";
                default: return "DATA";  // Generic type for other packets
            }
        }
    }

    static class Tk103Matcher implements ProtocolMatcher {
        private static final byte[] BEGIN_MARKER = {(byte) 0x24, (byte) 0x24};
        private static final byte[] END_MARKER = {0x0D, 0x0A};

        @Override
        public boolean matches(byte[] data) {
            if (data == null || data.length < 6) return false;

            // Check start and end markers
            return data[0] == BEGIN_MARKER[0] &&
                    data[1] == BEGIN_MARKER[1] &&
                    data[data.length-2] == END_MARKER[0] &&
                    data[data.length-1] == END_MARKER[1];
        }

        @Override
        public String getPacketType(byte[] data) {
            if (!matches(data)) return "UNKNOWN";

            // TK103 protocol type is in the content
            return "TK103"; // Further parsing would be needed for specific types
        }
    }

    static class TeltonikaMatcher implements ProtocolMatcher {
        @Override
        public boolean matches(byte[] data) {
            try {
                if (data == null || data.length < 12) return false;

                ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                long preamble = buffer.getInt();
                int length = buffer.getInt();

                return preamble == 0 &&
                        length > 0 &&
                        length <= data.length - 8;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public String getPacketType(byte[] data) {
            if (!matches(data)) return "UNKNOWN";

            // Teltonika protocol type requires deeper parsing
            return "TELTONIKA";
        }
    }
}