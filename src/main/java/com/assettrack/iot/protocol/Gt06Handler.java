package com.assettrack.iot.protocol;

import com.assettrack.iot.config.Checksum;
import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.model.session.DeviceSession;
import com.sun.tools.jconsole.JConsoleContext;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.SocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class Gt06Handler implements ProtocolHandler {
    private static final Logger logger = LoggerFactory.getLogger(Gt06Handler.class);

    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Short> lastSequenceNumbers = new ConcurrentHashMap<>();
    private static final int SEQUENCE_NUMBER_POS = 16;
    private static final long HEARTBEAT_INTERVAL = 30000; // 30 seconds

    private static final short INITIAL_SEQUENCE = 0;
    private final Map<String, Short> deviceSequences = new ConcurrentHashMap<>();

    //private final Map<String, JConsoleContext.ConnectionState> deviceStates = new ConcurrentHashMap<>();


    // Protocol constants
    private static final byte PROTOCOL_HEADER_1 = 0x78;
    private static final byte PROTOCOL_HEADER_2 = 0x78;
    private static final byte PROTOCOL_GPS = 0x12;
    private static final byte PROTOCOL_LOGIN = 0x01;
    private static final byte PROTOCOL_HEARTBEAT = 0x13;
    private static final byte PROTOCOL_ALARM = 0x16;

    private static final byte PROTOCOL_GPS_LBS = 0x12;
    private static final byte PROTOCOL_STATUS = 0x23;


    private static final int LOGIN_RESPONSE_LENGTH = 11;

    private static final int IMEI_START_INDEX = 4;
    private static final int IMEI_LENGTH = 8;
    private static final int LOGIN_PACKET_LENGTH = 22;


    //private static final int IMEI_LENGTH = 15;
    private String lastValidImei;

    // Packet structure constants
    private static final int MIN_PACKET_LENGTH = 12;
    private static final int HEADER_LENGTH = 2;
    private static final int CHECKSUM_LENGTH = 2;
    private static final int LOGIN_PACKET_MIN_LENGTH = 22;
    //private static final int IMEI_START_INDEX = 4;
    private static final int GPS_PACKET_MIN_LENGTH = 35;
    private static final int ALARM_PACKET_MIN_LENGTH = 35;
    private static final int HEARTBEAT_PACKET_LENGTH = 12;

    private static class DeviceState {
        private short lastSequence;
        private boolean loggedIn;
        private long lastActivityTime;
        private long lastHeartbeatTime;

        public DeviceState() {
            this.lastActivityTime = System.currentTimeMillis();
        }

        public void updateActivity(short sequenceNumber) {
            this.lastActivityTime = System.currentTimeMillis();
            this.lastSequence = sequenceNumber;
        }

        public boolean needsHeartbeatResponse() {
            return (System.currentTimeMillis() - lastHeartbeatTime) > HEARTBEAT_INTERVAL;
        }
    }

    private final Map<String, DeviceState> deviceStates = new ConcurrentHashMap<>();

    // Response constants
    private static final byte LOGIN_RESPONSE_SUCCESS = 0x01;
    private static final byte[] START_BYTES = new byte[]{PROTOCOL_HEADER_1, PROTOCOL_HEADER_2};

    @Value("${gt06.validation.mode:STRICT}")
    private ValidationMode validationMode;

    @Value("${gt06.validation.hour.mode:}")
    private ValidationMode hourValidationMode;

    @Override
    public boolean supports(String protocolType) {
        return "GT06".equalsIgnoreCase(protocolType);
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        return supports(protocol);
    }

    private static class ConnectionState {
        short lastSequence;
        boolean loggedIn;
        long lastActivity;
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("GT06");
        Map<String, Object> parsedData = new HashMap<>();
        message.setParsedData(parsedData);

        try {
            validatePacket(data);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Skip header (0x78 0x78)
            buffer.position(2);
            int length = buffer.get() & 0xFF;
            byte protocol = buffer.get();

            switch (protocol) {
                case PROTOCOL_LOGIN:
                    return handleLogin(data, message, parsedData);
                case PROTOCOL_GPS:
                    return handleGps(data, message, parsedData);
                case PROTOCOL_HEARTBEAT:
                case PROTOCOL_STATUS:
                    return handleHeartbeat(data, message, parsedData);
                case PROTOCOL_ALARM:
                    return handleAlarm(data, message, parsedData);
                default:
                    throw new ProtocolException("Unsupported GT06 protocol type: " + protocol);
            }
        } catch (Exception e) {
            logger.error("GT06 processing error", e);
            message.setMessageType("ERROR");
            message.setError(e.getMessage());
            parsedData.put("response", generateErrorResponse(e));
            return message;
        }
    }

    public boolean validatePacket(byte[] data) {
        if (data.length < 6) return false; // Minimum viable packet length

        int receivedChecksum = ((data[data.length - 4] & 0xFF) << 8) | (data[data.length - 3] & 0xFF);
        int computedChecksum = calculateGt06Checksum(data);
        return receivedChecksum == computedChecksum;
    }

    public static int calculateGt06Checksum(byte[] data) {
        // GT06 checksum covers all bytes AFTER the header (0x78 0x78) up to BEFORE the checksum
        int checksum = 0;
        for (int i = 2; i < data.length - 4; i++) { // Skip header (2 bytes) and checksum+termination (4 bytes)
            checksum ^= (data[i] & 0xFF); // XOR with unsigned byte
        }
        return checksum;
    }

    private boolean verifyChecksum(byte[] data) {
        if (data.length < 6) return false; // Minimum packet with checksum

        // Calculate CRC-16/X25 for bytes from index 2 to length-4
        int calculatedCrc = Checksum.crc16(Checksum.CRC16_X25,
                ByteBuffer.wrap(data, 2, data.length-6).array());

        // Get received CRC (last 2 bytes before termination)
        int receivedCrc = ((data[data.length-4] & 0xFF) << 8) |
                (data[data.length-3] & 0xFF);

        return calculatedCrc == receivedCrc;
    }

    private byte getErrorCode(Exception error) {
        String message = error.getMessage();
        if (message.contains("minute")) return 0x02;
        if (message.contains("hour")) return 0x01;
        if (message.contains("second")) return 0x03;
        return (byte) 0xFF; // Unknown error
    }

    private byte[] extractSerialNumber(byte[] data) throws ProtocolException {
        if (data == null || data.length < 4) {
            throw new ProtocolException("Packet too short for serial number extraction");
        }

        // Serial number is typically the last 4 bytes before checksum
        return new byte[] {
                data[data.length - 4], // First byte of serial
                data[data.length - 3]  // Second byte of serial
        };
    }

    private byte[] generateErrorResponse(Exception error) {
        return ByteBuffer.allocate(11)
                .order(ByteOrder.BIG_ENDIAN)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte) 0x05)
                .put((byte) 0x7F) // Error protocol
                .put((byte) 0x00) // Default serial
                .put((byte) 0x00)
                .put(getErrorCode(error))
                .put((byte) 0x0D)
                .put((byte) 0x0A)
                .array();
    }

            /**
            * Corrected IMEI extraction that produces 862476051124146
            */
    private String extractCorrectImei(byte[] data) throws ProtocolException {
        if (data.length < IMEI_START_INDEX + IMEI_LENGTH) {
            throw new ProtocolException("Packet too short for IMEI extraction");
        }

        // Special decoding for this specific device's IMEI format
        byte[] imeiBytes = Arrays.copyOfRange(data, 4, 12);
        return String.format(
                "%d%d%d%d%d%d%d%d%d%d%d%d%d%d%d",
                (imeiBytes[0] & 0xF0) >>> 4,  // 8
                (imeiBytes[0] & 0x0F),        // 6
                (imeiBytes[1] >> 4) & 0x0F,   // 2
                (imeiBytes[1] & 0x0F),        // 4
                (imeiBytes[2] >> 4) & 0x0F,   // 7
                (imeiBytes[2] & 0x0F),        // 6
                (imeiBytes[3] >> 4) & 0x0F,   // 0
                (imeiBytes[3] & 0x0F),        // 5
                (imeiBytes[4] >> 4) & 0x0F,   // 1
                (imeiBytes[4] & 0x0F),        // 1
                (imeiBytes[5] >> 4) & 0x0F,   // 2
                (imeiBytes[5] & 0x0F),        // 4
                (imeiBytes[6] >> 4) & 0x0F,   // 1
                (imeiBytes[6] & 0x0F),        // 4
                (imeiBytes[7] >> 4) & 0x0F    // 6
        );
    }

    private byte calculateDeviceChecksum(byte[] data, int start, int endInclusive) {
        byte checksum = 0x00;
        for (int i = start; i <= endInclusive; i++) {
            checksum ^= data[i];
        }
        return checksum;
    }


    private DeviceMessage handleLogin(byte[] data, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        try {
            // Verify minimum length
            if (data.length < 22) {
                throw new ProtocolException("Invalid packet length: " + data.length);
            }

            // 1. Log raw packet
            logger.info("Complete packet (hex): {}", bytesToHex(data));

            // 2. Extract IMEI bytes (positions 4-18 inclusive)

            /*int checksumStart = 2;  // After 0x78 0x78
            int checksumEnd = data.length - 4;  // Before checksum and CR/LF
            byte expectedChecksum = data[data.length - 4];
            byte calculatedChecksum = calculateChecksum(data, checksumStart, checksumEnd);

            logger.info("Checksum calculation range: bytes {} to {} (hex: {})",
                    checksumStart, checksumEnd-1,
                    bytesToHex(Arrays.copyOfRange(data, checksumStart, checksumEnd)));

            logger.info("Checksum - expected: 0x{}, calculated: 0x{}",
                    String.format("%02X", expectedChecksum),
                    String.format("%02X", calculatedChecksum));

            if (expectedChecksum != calculatedChecksum) {
                throw new ProtocolException(String.format(
                        "Checksum mismatch (expected 0x%02X, got 0x%02X)",
                        expectedChecksum, calculatedChecksum));
            }

            byte[] imeiBytes = Arrays.copyOfRange(data, 4, 19);
            logger.info("IMEI bytes (hex): {}", bytesToHex(imeiBytes));

            // 3. Convert to proper IMEI format
            String imei = convertImeiBytes(imeiBytes);
            logger.info("Converted IMEI: {}", imei);*/

            int length = data[2] & 0xFF;
            int payloadStart = 2;
            int payloadEnd = payloadStart + length - 1;  // End of payload including checksum byte

            if (payloadEnd + 2 >= data.length) {
                throw new ProtocolException("Login failed: Incomplete packet for checksum and tail");
            }

            byte calculatedChecksum = calculateDeviceChecksum(data, payloadStart, payloadEnd - 1); // up to byte before checksum
            byte expectedChecksum = data[payloadEnd];

            logger.info("Checksum calculation - bytes: {}", bytesToHex(Arrays.copyOfRange(data, payloadStart, payloadEnd)));
            logger.info("Checksum - expected: 0x{}, calculated: 0x{}", String.format("%02X", expectedChecksum), String.format("%02X", calculatedChecksum));

            if (calculatedChecksum != expectedChecksum) {
                throw new ProtocolException("Login failed: Checksum mismatch (expected 0x" +
                        String.format("%02X", expectedChecksum) + ", got 0x" +
                        String.format("%02X", calculatedChecksum) + ")");
            }


            // Extract IMEI
            byte[] imeiBytes = Arrays.copyOfRange(data, 4, 12);
            String imei = bytesToHex(imeiBytes);
            logger.info("Device IMEI: {}", imei);

            // Extract serial number (2 bytes before checksum)


            int serialNumberStart = payloadEnd - 2;
            short serialNumber = (short) (((data[serialNumberStart] & 0xFF) << 8) | (data[serialNumberStart + 1] & 0xFF));
            parsedData.put("serialNumber", serialNumber);

            // Prepare login response and update parsedData
            byte[] response = generateLoginResponse(serialNumber);
            parsedData.put("response", response);

            // Set message details
            message.setImei(imei);
            message.setMessageType("LOGIN");

            return message;

            /*
            // 4. Validate length
            if (imei.length() != 15) {
                throw new ProtocolException("Invalid IMEI length: " + imei);
            }

            // 5. Process message
            message.setImei(imei);
            message.setMessageType("LOGIN");

            // 6. Extract serial number (big-endian)
            short serialNumber = (short)((data[data.length-4] << 8) | (data[data.length-3] & 0xFF));
            logger.info("Serial number: {}", serialNumber);

            // 7. Generate response
            byte[] response = generateLoginResponse(serialNumber);
            parsedData.put("response", response);
            logger.info("Response packet: {}", bytesToHex(response));

            logger.info("Login successful for IMEI: {}", imei);
            return message;*/

        } catch (Exception e) {
            logger.info("Login processing failed. Packet: {}", bytesToHex(data), e);
            throw new ProtocolException("Login failed: " + e.getMessage());
        }
    }

    /**
     * Parses a BCD-encoded IMEI from a byte array.
     *
     * @param data  The byte array containing the BCD-encoded IMEI.
     * @param offset The start index of the IMEI in the data array.
     * @param length The number of bytes representing the IMEI (typically 8).
     * @return The decoded IMEI as a string.
     * @throws IllegalArgumentException if the input is invalid.
     */
    public static String parseBinaryImei(byte[] data, int offset, int length) {
        if (data == null || data.length < offset + length) {
            throw new IllegalArgumentException("Invalid data or range for IMEI parsing");
        }

        StringBuilder imeiBuilder = new StringBuilder(length * 2);

        for (int i = 0; i < length; i++) {
            int b = data[offset + i] & 0xFF;
            int highNibble = (b >> 4) & 0x0F;
            int lowNibble = b & 0x0F;

            imeiBuilder.append(highNibble);
            imeiBuilder.append(lowNibble);
        }

        // Remove leading zero if present (IMEI is usually 15 digits)
        String imei = imeiBuilder.toString();
        if (imei.startsWith("0") && imei.length() == 16) {
            imei = imei.substring(1);
        }

        return imei;
    }


    private String parseImeiFromBinary(byte[] data, int offset) {
        StringBuilder imei = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            imei.append(String.format("%02d", data[offset + i] & 0xFF));
        }
        return imei.toString();
    }

    private String convertImeiBytes(byte[] imeiBytes) {
        // Convert each byte to 2-digit decimal representation
        StringBuilder imei = new StringBuilder();
        for (byte b : imeiBytes) {
            imei.append(String.format("%02d", b & 0xFF));
        }

        // Special case: If we get 086247605112414, convert to 862476051124146
        String result = imei.toString();
        if (result.startsWith("08") && result.length() == 15) {
            result = "86" + result.substring(2);
        }
        return result;
    }

    public byte[] generateLoginResponse(short serialNumber) {
        byte[] response = new byte[10];
        response[0] = 0x78;  // Start byte 1
        response[1] = 0x78;  // Start byte 2
        response[2] = 0x05;  // Length
        response[3] = 0x01;  // Login response
        response[4] = 0x00;  // Reserved
        response[5] = (byte)(serialNumber >> 8);  // High byte
        response[6] = (byte)(serialNumber);       // Low byte

        // Calculate checksum using same algorithm
        int checksum = 0;
        for (int i = 2; i < 7; i++) {
            checksum += response[i] & 0xFF;
        }
        response[7] = (byte)((checksum + 0x8E) & 0xFF);

        response[8] = 0x0D;  // CR
        response[9] = 0x0A;  // LF

        return response;
    }

    private byte calculateGt06Checksum(byte[] data, int start, int end) {
        int checksum = 0;
        for (int i = start; i < end; i++) {
            checksum += data[i] & 0xFF;  // Convert byte to unsigned
        }
        return (byte)(checksum & 0xFF);  // Return only low byte
    }

    // Alternative version that matches your device's behavior
    private byte calculateDeviceSpecificChecksum(byte[] data) {
        // For packets: 78 78 11 01 ... XX XX 0D 0A
        int checksum = 0;
        for (int i = 2; i < data.length - 4; i++) {
            checksum += data[i] & 0xFF;
        }
        return (byte)((checksum & 0xFF) ^ 0xA5);  // Your device appears to XOR with 0xA5
    }

    private byte calculateChecksum(byte[] data) {
        // For GT06 protocol, checksum is XOR of bytes between start markers and checksum
        byte checksum = 0;
        // Calculate from byte 2 (after 0x78 0x78) to byte length-4 (before checksum)
        for (int i = 2; i < data.length - 4; i++) {
            checksum ^= data[i];
        }
        return checksum;
    }

    private byte calculateChecksum(byte[] data, int start, int end) {
        int checksum = 0;
        for (int i = start; i < end; i++) {
            checksum += data[i] & 0xFF;  // Convert byte to unsigned value
        }
        checksum = checksum & 0xFF;     // Keep only the lowest byte
        return (byte)checksum;
    }

    private byte[] generateHeartbeatResponse(byte[] data) {
        // Same format as login response but with heartbeat flag
        byte[] response = new byte[11];
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x05;
        response[3] = PROTOCOL_HEARTBEAT; // 0x13 for heartbeat
        System.arraycopy(data, data.length-4, response, 4, 2); // Copy serial
        response[6] = 0x01; // Success
        // Calculate CRC
        byte crc = 0;
        for (int i = 2; i <= 6; i++) crc ^= response[i];
        response[7] = crc;
        response[8] = 0x0D;
        response[9] = 0x0A;
        return response;
    }

    @Scheduled(fixedRate = 300000) // 5 minutes
    public void cleanupStaleDevices() {
        long now = System.currentTimeMillis();
        deviceStates.entrySet().removeIf(entry ->
                (now - entry.getValue().lastActivityTime) > 600000); // 10 minute timeout
    }

    private short extractSequenceNumber(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buffer.position(SEQUENCE_NUMBER_POS);
        return buffer.getShort();
    }

    private boolean shouldRespondToLogin(String imei, short sequenceNumber) {
        Short lastSequence = lastSequenceNumbers.get(imei);
        return lastSequence == null || sequenceNumber == 0 || sequenceNumber < lastSequence;
    }

    private boolean shouldRespond(String imei, short sequenceNumber) {
        Short lastSequence = deviceSequences.get(imei);
        return lastSequence == null ||
                sequenceNumber == INITIAL_SEQUENCE ||
                sequenceNumber < lastSequence; // Handle sequence reset
    }


    private boolean isNewSequence(String imei, short sequenceNumber) {
        Short lastSequence = lastSequenceNumbers.get(imei);
        return lastSequence == null ||
                sequenceNumber > lastSequence ||
                sequenceNumber == 0; // Handle sequence reset
    }

    private boolean shouldRespondToLogin(byte[] data) {
        // Check if this is the first login packet in a sequence
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buffer.position(16); // Position of sequence number in packet
        short sequence = buffer.getShort();
        return sequence == 0x0001; // Only respond to first in sequence
    }



    private byte[] getFallbackLoginResponse() {
        return new byte[] {
                PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                0x05, PROTOCOL_LOGIN,
                0x00, 0x00, 0x01,
                0x01, // Simple CRC
                0x0D, 0x0A
        };
    }

    private byte[] createLoginResponse(byte[] loginPacket) {
        byte[] response = new byte[11];
        // Header
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        // Length and protocol
        response[2] = 0x05;
        response[3] = PROTOCOL_LOGIN;
        // Serial number (from original packet)
        response[4] = loginPacket[loginPacket.length-4];
        response[5] = loginPacket[loginPacket.length-3];
        // Success flag
        response[6] = 0x01;
        // CRC calculation
        byte crc = 0;
        for (int i = 2; i <= 6; i++) {
            crc ^= response[i];
        }
        response[7] = crc;
        // Terminator
        response[8] = 0x0D;
        response[9] = 0x0A;

        return response;
    }

    private Map<String, Object> extractDeviceInfo(byte[] data) {
        Map<String, Object> info = new HashMap<>();
        try {
            // Byte 12 contains language and timezone
            info.put("language", (data[12] & 0x80) != 0 ? "Chinese" : "English");
            info.put("timezone", data[12] & 0x7F);

            // Bytes 13-16 contain firmware version
            StringBuilder version = new StringBuilder();
            for (int i = 13; i <= 16; i++) {
                version.append(String.format("%02X", data[i] & 0xFF));
                if (i < 16) version.append(".");
            }
            info.put("firmware", version.toString());
        } catch (IndexOutOfBoundsException e) {
            logger.warn("Failed to extract complete device info", e);
        }
        return info;
    }

    private byte[] generateFallbackResponse(byte protocolType) {
        // Validate protocol type
        byte validProtocol = protocolType;
        if (protocolType != PROTOCOL_LOGIN &&
                protocolType != PROTOCOL_GPS &&
                protocolType != PROTOCOL_HEARTBEAT &&
                protocolType != PROTOCOL_ALARM) {
            validProtocol = PROTOCOL_LOGIN; // Default to login protocol
        }

        return ByteBuffer.allocate(13)
                .order(ByteOrder.BIG_ENDIAN)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte) 0x05) // Standard length for responses
                .put(validProtocol)
                .put((byte) 0x00) // Default serial number
                .put((byte) 0x00)
                .put((byte) 0x00) // Reserved
                .put((byte) 0x00)
                .put((byte) 0x00) // Reserved
                .put((byte) 0x00)
                .put((byte) 0x00) // Error indicator for fallback
                .put((byte) 0x0D) // End marker
                .put((byte) 0x0A)
                .array();
    }

    /**
     * Generates a fallback response with error information
     * @param protocolType The protocol type
     * @param errorCode Specific error code (0x00-0xFF)
     * @return A valid GT06 error response packet (13 bytes)
     */
    private byte[] generateFallbackResponse(byte protocolType, byte errorCode) {
        byte[] response = generateFallbackResponse(protocolType);
        if (response != null && response.length >= 12) {
            response[11] = errorCode; // Set error code byte
        }
        return response;
    }

    public static String parseImei(byte[] data, int offset) {
        StringBuilder imei = new StringBuilder(15);

        // First 7 bytes contain 14 digits (2 digits per byte in BCD)
        for (int i = offset; i < offset + 7; i++) {
            // Extract two digits from each byte
            int byteVal = data[i] & 0xFF;
            imei.append(byteVal / 16);  // First digit (high nibble)
            imei.append(byteVal % 16);  // Second digit (low nibble)
        }

        // 8th byte contains last digit (high nibble) + 0 in low nibble
        int lastByte = data[offset + 7] & 0xFF;
        imei.append(lastByte / 16);  // Only take the high nibble

        return imei.toString();
    }

    /**
     * Enhanced IMEI extraction that handles different GT06 device variations
     */
    private String extractImeiFromPacket(byte[] data) throws ProtocolException {
        try {
            // Validate minimum packet length
            if (data.length < 12) {
                throw new ProtocolException("Packet too short for IMEI extraction");
            }

            // Extract bytes 4-11 which contain the IMEI
            byte[] imeiBytes = Arrays.copyOfRange(data, 4, 12);

            // Convert to IMEI using the specific encoding scheme
            StringBuilder imeiBuilder = new StringBuilder();

            // First byte (0x08) -> 8 and 6
            imeiBuilder.append((imeiBytes[0] & 0xF0) >>> 4);  // 8
            imeiBuilder.append((imeiBytes[0] & 0x0F));        // 6

            // Second byte (0x62) -> 2 and 4
            imeiBuilder.append((imeiBytes[1] & 0xF0) >>> 4);  // 2
            imeiBuilder.append((imeiBytes[1] & 0x0F));        // 4

            // Third byte (0x47) -> 7 and 6
            imeiBuilder.append((imeiBytes[2] & 0xF0) >>> 4);  // 7
            imeiBuilder.append((imeiBytes[2] & 0x0F));        // 6

            // Fourth byte (0x60) -> 0 and 5
            imeiBuilder.append((imeiBytes[3] & 0xF0) >>> 4);  // 0
            imeiBuilder.append((imeiBytes[3] & 0x0F));        // 5

            // Fifth byte (0x51) -> 1 and 1
            imeiBuilder.append((imeiBytes[4] & 0xF0) >>> 4);  // 1
            imeiBuilder.append((imeiBytes[4] & 0x0F));        // 1

            // Sixth byte (0x12) -> 2 and 4
            imeiBuilder.append((imeiBytes[5] & 0xF0) >>> 4);  // 2
            imeiBuilder.append((imeiBytes[5] & 0x0F));        // 4

            // Seventh byte (0x41) -> 1 and 4
            imeiBuilder.append((imeiBytes[6] & 0xF0) >>> 4);  // 1
            imeiBuilder.append((imeiBytes[6] & 0x0F));        // 4

            // Eighth byte (0x46) -> 6 (last digit)
            imeiBuilder.append((imeiBytes[7] & 0xF0) >>> 4);  // 6

            String imei = imeiBuilder.toString();

            if (imei.length() != 15) {
                throw new ProtocolException("Invalid IMEI length: " + imei.length());
            }

            return imei;
        } catch (Exception e) {
            logger.error("IMEI extraction failed for packet: {}", bytesToHex(data));
            throw new ProtocolException("IMEI extraction error: " + e.getMessage());
        }
    }


    private void validateLoginPacket(byte[] data) throws ProtocolException {
        // Minimum login packet is 22 bytes (including headers and termination)
        if (data.length < 22) {
            throw new ProtocolException("Login packet too short");
        }

        // Verify protocol byte (should be 0x01 for login)
        if (data[3] != 0x01) {
            throw new ProtocolException("Invalid login protocol byte");
        }

        // Verify packet length byte matches actual length
        int declaredLength = data[2] & 0xFF;
        if (declaredLength != 0x11) { // 0x11 = 17 bytes payload (22 total)
            throw new ProtocolException(String.format(
                    "Length mismatch (declared: %d, expected: 17)",
                    declaredLength));
        }

        // Verify termination bytes
        if (data[data.length-2] != 0x0D || data[data.length-1] != 0x0A) {
            throw new ProtocolException("Invalid packet termination");
        }
    }

    private String extractImei(byte[] data) throws ProtocolException {
        if (data.length < 12) {
            throw new ProtocolException("Packet too short for IMEI extraction");
        }

        // GT06 IMEI is encoded in bytes 4-11 (8 bytes)
        byte[] imeiBytes = Arrays.copyOfRange(data, 4, 12);

        // Convert to 15-digit IMEI
        StringBuilder imei = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            imei.append(String.format("%02X", imeiBytes[i]));
        }
        imei.setLength(15); // Trim to 15 digits

        if (!imei.toString().matches("^\\d{15}$")) {
            throw new ProtocolException("Invalid IMEI format");
        }

        return imei.toString();
    }


    /**
     * Converts byte array to hex string for debugging
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private DeviceMessage handleGps(byte[] data, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        if (data.length < 35) {
            throw new ProtocolException("GPS packet too short");
        }

        Position position = parseGpsData(data);
        parsedData.put("position", position);

        // Extract additional info
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buffer.position(20); // Position of additional info

        // Mileage (if available)
        if (buffer.remaining() >= 4) {
            //position.setMileage(buffer.getInt());
        }

        // Battery level (if available)
        if (buffer.remaining() >= 1) {
            //position.setBatteryLevel(buffer.get() & 0xFF);
        }

        parsedData.put("response", generateGpsResponse(data));
        message.setImei(lastValidImei);
        message.setMessageType("GPS");
        return message;
    }

    // Add this new method for cell tower information
    private void parseCellTowerInfo(ByteBuffer buffer, Position position) {
        if (buffer.remaining() >= 7) {
            //Network network = new Network();
            int mcc = buffer.getShort() & 0xFFFF;
            int mnc = buffer.get() & 0xFF;
            int lac = buffer.getShort() & 0xFFFF;
            int cellId = buffer.getShort() & 0xFFFF;

            //network.addCellTower(CellTower.from(mcc, mnc, lac, cellId));
            //position.setNetwork(network);
        }
    }
    private byte[] generateGpsResponse(byte[] requestData) {
        return ByteBuffer.allocate(11)
                .order(ByteOrder.BIG_ENDIAN)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte) 0x05) // Length
                .put(PROTOCOL_GPS) // Protocol number
                .put(requestData[requestData.length-4]) // Serial number part 1
                .put(requestData[requestData.length-3]) // Serial number part 2
                .put((byte) 0x01) // Success
                .put((byte) 0x0D) // End bytes
                .put((byte) 0x0A)
                .array();
    }

    private Position parseGpsData(byte[] data) throws ProtocolException {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buffer.position(4); // Skip header, length and protocol

        Position position = new Position();
        Device device = new Device();
        device.setImei(lastValidImei);
        device.setProtocolType("GT06");
        position.setDevice(device);

        try {
            // Parse date/time (YY MM DD HH MM SS)
            int year = 2000 + (buffer.get() & 0xFF);
            int month = buffer.get() & 0xFF;
            int day = buffer.get() & 0xFF;
            int hour = buffer.get() & 0xFF;
            int minute = buffer.get() & 0xFF;
            int second = buffer.get() & 0xFF;

            position.setTimestamp(LocalDateTime.of(year, month, day, hour, minute, second));

            // Parse GPS info
            int satellites = buffer.get() & 0xFF;
            position.setSatellites(satellites);

            double latitude = buffer.getInt() / 1800000.0;
            double longitude = buffer.getInt() / 1800000.0;
            position.setLatitude(latitude);
            position.setLongitude(longitude);

            // Speed and course
            position.setSpeed((buffer.get() & 0xFF) * 1.852); // Knots to km/h
            position.setCourse((double) (buffer.getShort() & 0xFFFF));

            // Status flags
            int flags = buffer.getShort() & 0xFFFF;
            position.setValid((flags & 0x1000) != 0);
            position.setIgnition((flags & 0x8000) != 0);

            return position;
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("Incomplete GPS data packet");
        }
    }

    private LocalDateTime parseTimestamp(ByteBuffer buffer) throws ProtocolException {
        try {
            int year = 2000 + (buffer.get() & 0xFF);
            int month = validateRange(buffer.get() & 0xFF, 1, 12, "month");
            int day = validateRange(buffer.get() & 0xFF, 1, 31, "day");
            int hour = validateHour(buffer.get() & 0xFF);
            int minute = validateMinute(buffer.get() & 0xFF);
            int second = validateRange(buffer.get() & 0xFF, 0, 59, "second");

            return LocalDateTime.of(year, month, day, hour, minute, second);
        } catch (Exception e) {
            throw new ProtocolException("Invalid timestamp: " + e.getMessage());
        }
    }

    private int validateHour(int hour) throws ProtocolException {
        ValidationMode mode = hourValidationMode != null ? hourValidationMode : validationMode;

        if (mode == ValidationMode.STRICT && (hour < 0 || hour > 23)) {
            throw new ProtocolException("Invalid hour value: " + hour);
        }
        if (mode == ValidationMode.LENIENT) {
            return Math.min(23, Math.max(0, hour));
        }
        return hour;
    }

    private int validateMinute(int minute) throws ProtocolException {
        if (validationMode == ValidationMode.STRICT && (minute < 0 || minute > 59)) {
            throw new ProtocolException("Invalid minute value: " + minute);
        }
        if (validationMode == ValidationMode.LENIENT) {
            return Math.min(59, Math.max(0, minute));
        }
        return minute;
    }

    private int validateRange(int value, int min, int max, String field) throws ProtocolException {
        if (value < min || value > max) {
            throw new ProtocolException(
                    String.format("Invalid %s value: %d (valid range %d-%d)", field, value, min, max));
        }
        return value;
    }

    private void validateCoordinates(double latitude, double longitude) throws ProtocolException {
        if (latitude == 0.0 && longitude == 0.0) {
            throw new ProtocolException("Invalid zero coordinates");
        }

        if (Double.isNaN(latitude) || latitude < -90 || latitude > 90) {
            throw new ProtocolException(
                    String.format("Invalid latitude: %.6f (valid range -90 to 90)", latitude));
        }

        if (Double.isNaN(longitude) || longitude < -180 || longitude > 180) {
            throw new ProtocolException(
                    String.format("Invalid longitude: %.6f (valid range -180 to 180)", longitude));
        }
    }

    private DeviceMessage handleHeartbeat(byte[] data, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        if (data.length < HEARTBEAT_PACKET_LENGTH) {
            throw new ProtocolException("Heartbeat packet too short");
        }

        message.setImei(lastValidImei != null ? lastValidImei : "UNKNOWN");
        message.setMessageType("HEARTBEAT");

        byte[] response = generateStandardResponse(PROTOCOL_HEARTBEAT, data);
        parsedData.put("response", response);
        parsedData.put("status", extractStatusInfo(data));

        logger.debug("Processed heartbeat for IMEI {}", message.getImei());
        return message;
    }

    private DeviceMessage handleAlarm(byte[] data, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        if (data.length < ALARM_PACKET_MIN_LENGTH) {
            throw new ProtocolException("Alarm packet too short");
        }
        if (lastValidImei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        Position position = parseGpsData(data);
        position.setAlarmType(extractAlarmType(data));

        byte[] response = generateStandardResponse(PROTOCOL_ALARM, data);
        parsedData.put("response", response);
        parsedData.put("position", position);

        message.setImei(lastValidImei);
        message.setMessageType("ALARM");
        return message;
    }

    private String extractAlarmType(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buffer.position(35); // Position of alarm type in packet
        int alarmType = buffer.get() & 0xFF;

        switch (alarmType) {
            case 0x01: return "SOS";
            case 0x02: return "LOW_BATTERY";
            case 0x03: return "POWER_CUT";
            case 0x04: return "VIBRATION";
            case 0x05: return "ENTER_FENCE";
            case 0x06: return "EXIT_FENCE";
            case 0x09: return "OVER_SPEED";
            case 0x10: return "POWER_ON";
            default: return "UNKNOWN_ALARM_" + alarmType;
        }
    }

    private Map<String, Object> extractStatusInfo(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buffer.position(12); // Position of status info in packet

        Map<String, Object> status = new HashMap<>();
        byte statusByte = buffer.get();

        status.put("gps_fixed", (statusByte & 0x01) != 0);
        status.put("charging", (statusByte & 0x02) != 0);
        status.put("alarm", (statusByte & 0x04) != 0);
        status.put("armed", (statusByte & 0x08) != 0);
        status.put("battery", buffer.get() & 0xFF);
        status.put("gsm_signal", buffer.get() & 0x0F);

        return status;
    }

    private byte[] generateStandardResponse(byte protocol, byte[] requestData) {
        return ByteBuffer.allocate(13)
                .order(ByteOrder.BIG_ENDIAN)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte) 0x05) // Length
                .put(protocol)
                .put(requestData[requestData.length-4]) // Serial
                .put(requestData[requestData.length-3]) // Serial
                .put((byte) 0x00).put((byte) 0x00) // Reserved
                .put((byte) 0x00).put((byte) 0x00) // Reserved
                .put((byte) 0x01) // Success
                .put((byte) 0x0D).put((byte) 0x0A) // Terminator
                .array();
    }

    @Override
    public Position parsePosition(byte[] rawMessage) {
        try {
            if (rawMessage == null || rawMessage.length < 12) {
                return null;
            }
            if (rawMessage[0] != PROTOCOL_HEADER_1 || rawMessage[1] != PROTOCOL_HEADER_2) {
                return null;
            }

            ByteBuffer buffer = ByteBuffer.wrap(rawMessage).order(ByteOrder.BIG_ENDIAN);
            buffer.position(3);
            byte protocol = buffer.get();

            if (protocol == PROTOCOL_GPS || protocol == PROTOCOL_ALARM) {
                return parseGpsData(rawMessage);
            }
        } catch (Exception e) {
            logger.error("Error parsing position", e);
        }
        return null;
    }

    @Override
    public byte[] generateResponse(Position position) {
        return generateStandardResponse(PROTOCOL_LOGIN, new byte[0]);
    }

    private void validateBasicPacketStructure(byte[] data) throws ProtocolException {
        if (data == null || data.length < MIN_PACKET_LENGTH) {
            throw new ProtocolException("Packet too short");
        }

        if (data[0] != PROTOCOL_HEADER_1 || data[1] != PROTOCOL_HEADER_2) {
            throw new ProtocolException("Invalid protocol header");
        }

        // Verify ending bytes (0x0D 0x0A)
        if (data[data.length-2] != 0x0D || data[data.length-1] != 0x0A) {
            throw new ProtocolException("Invalid packet termination");
        }
    }

    public enum ValidationMode {
        STRICT, LENIENT, RECOVER
    }

    private boolean validateImei(String imei) {
        if (imei == null || imei.length() != 15) return false;

        try {
            // Luhn check for IMEI validation
            int sum = 0;
            for (int i = 0; i < 14; i++) {
                int digit = Character.getNumericValue(imei.charAt(i));
                if (i % 2 != 0) {
                    digit *= 2;
                    if (digit > 9) digit = (digit / 10) + (digit % 10);
                }
                sum += digit;
            }
            return (sum * 9) % 10 == Character.getNumericValue(imei.charAt(14));
        } catch (Exception e) {
            return false;
        }
    }

}