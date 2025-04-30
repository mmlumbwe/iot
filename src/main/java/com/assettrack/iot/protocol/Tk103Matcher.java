package com.assettrack.iot.protocol;


import java.util.regex.Pattern;

/**
 * Matcher implementation for TK103 protocol detection
 *
 * Features:
 * - Validates TK103 message structure
 * - Verifies header/footer markers (##)
 * - Checks minimum message length
 * - Validates basic message format
 */
public class Tk103Matcher implements ProtocolDetector.ProtocolMatcher {
    // TK103 protocol constants
    private static final byte[] HEADER = {'#', '#'};
    private static final int MIN_MESSAGE_LENGTH = 20;
    private static final Pattern MESSAGE_PATTERN = Pattern.compile(
            "^##(.+?),([^,]+),(\\d+\\.\\d+),([-+]?\\d+\\.\\d+),(\\d{12}),([AV]),.*?##$");

    @Override
    public boolean matches(byte[] data) {
        if (data == null || data.length < MIN_MESSAGE_LENGTH) {
            return false;
        }

        // Verify header and footer markers
        if (data[0] != HEADER[0] || data[1] != HEADER[1] ||
                data[data.length - 2] != HEADER[0] || data[data.length - 1] != HEADER[1]) {
            return false;
        }

        // Convert to string for format validation
        try {
            String message = new String(data).trim();
            return MESSAGE_PATTERN.matcher(message).matches();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getPacketType(byte[] data) {
        return "";
    }


}
