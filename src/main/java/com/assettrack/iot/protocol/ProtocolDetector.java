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
            "TK103", new Tk103Matcher()
    );

    public String detectProtocol(byte[] data) {
        if (data == null || data.length < 2) {
            return "UNKNOWN";
        }

        // Check for GT06 first
        if (data[0] == 0x78 && data[1] == 0x78) {
            return "GT06";
        }

        // Check for TK103
        if (data[0] == 0x23 && data[1] == 0x23) {
            return "TK103";
        }

        // Check for TK103 location (starts with IMEI)
        String message = new String(data, StandardCharsets.US_ASCII);
        if (message.matches("^\\d{15},.*")) {
            return "TK103";
        }

        return "UNKNOWN";
    }

    public String getPacketType(byte[] data) {
        String protocol = detectProtocol(data);

        if ("GT06".equals(protocol)) {
            if (data.length > 3) {
                switch (data[3] & 0xFF) {
                    case 0x01: return "LOGIN";
                    case 0x12: return "LOCATION";
                    case 0x13: return "HEARTBEAT";
                    case 0x16: return "ALARM";
                    default: return "UNKNOWN";
                }
            }
        } else if ("TK103".equals(protocol)) {
            String message = new String(data, StandardCharsets.US_ASCII);
            return message.startsWith("##") ? "LOGIN" : "LOCATION";
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
            return data[0] == 0x78 &&
                    data[1] == 0x78 &&
                    data[data.length-2] == 0x0D &&
                    data[data.length-1] == 0x0A;
        }

        @Override
        public String getPacketType(byte[] data) {
            if (!matches(data)) return "UNKNOWN";
            switch (data[3] & 0xFF) {
                case 0x01: return "LOGIN";
                case 0x12: return "LOCATION";
                case 0x13: return "HEARTBEAT";
                case 0x16: return "ALARM";
                default: return "DATA";
            }
        }
    }

    static class Tk103Matcher implements ProtocolMatcher {
        @Override
        public boolean matches(byte[] data) {
            if (data == null || data.length < 6) return false;
            return data[0] == 0x23 &&
                    data[1] == 0x23 &&
                    data[data.length-2] == 0x0D &&
                    data[data.length-1] == 0x0A;
        }

        @Override
        public String getPacketType(byte[] data) {
            return matches(data) ? "TK103" : "UNKNOWN";
        }
    }
}