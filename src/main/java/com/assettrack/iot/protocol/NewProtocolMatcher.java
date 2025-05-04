package com.assettrack.iot.protocol;

public class NewProtocolMatcher implements ProtocolDetector.ProtocolMatcher {
    private static final int MIN_PACKET_LENGTH = 57;
    private static final byte[] SIGNATURE = {0x00, 0x00, 0x00, 0x33};

    @Override
    public boolean matches(byte[] data) throws ProtocolDetector.ProtocolDetectionException {
        if (data == null || data.length < MIN_PACKET_LENGTH) {
            return false;
        }

        // Check signature bytes
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (data[i] != SIGNATURE[i]) {
                return false;
            }
        }

        // Additional validation can be added here
        return true;
    }

    @Override
    public String getPacketType(byte[] data) throws ProtocolDetector.ProtocolDetectionException {
        if (!matches(data)) {
            throw new ProtocolDetector.ProtocolDetectionException("Not a NEW_PROTOCOL packet");
        }

        // Analyze packet to determine type
        // Example: check byte at position 8 for packet type code
        int packetTypeCode = data[8] & 0xFF;

        switch (packetTypeCode) {
            case 0x01: return "LOCATION";
            case 0x02: return "STATUS";
            case 0x03: return "ALARM";
            default: return "DATA";
        }
    }
}
