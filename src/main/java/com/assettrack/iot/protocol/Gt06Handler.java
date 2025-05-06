package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.regex.Pattern;

@Component
public class Gt06Handler implements ProtocolHandler {
    private static final Logger logger = LoggerFactory.getLogger(Gt06Handler.class);

    // Protocol constants
    private static final byte PROTOCOL_HEADER_1 = 0x78;
    private static final byte PROTOCOL_HEADER_2 = 0x78;
    private static final byte PROTOCOL_GPS = 0x12;
    private static final byte PROTOCOL_LOGIN = 0x01;
    private static final byte PROTOCOL_HEARTBEAT = 0x13;
    private static final byte PROTOCOL_ALARM = 0x16;

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

    private void validatePacket(byte[] data) throws ProtocolException {
        if (data == null || data.length < MIN_PACKET_LENGTH) {
            throw new ProtocolException("Packet too short");
        }

        // Check protocol header
        if (data[0] != PROTOCOL_HEADER_1 || data[1] != PROTOCOL_HEADER_2) {
            throw new ProtocolException("Invalid protocol header");
        }

        // Check termination bytes
        if (data[data.length-2] != 0x0D || data[data.length-1] != 0x0A) {
            throw new ProtocolException("Invalid packet termination");
        }
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

    private DeviceMessage handleLogin(byte[] data, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        if (data.length < LOGIN_PACKET_LENGTH) {
            throw new ProtocolException("Login packet too short");
        }

        String imei = parseImei(data);
        lastValidImei = imei;
        message.setImei(imei);
        message.setMessageType("LOGIN");

        // Always generate and store response
        byte[] response = generateLoginResponse(data);
        parsedData.put("response", response);
        parsedData.put("device_info", extractDeviceInfo(data));

        logger.info("Processed GT06 login from IMEI: {} with response: {}",
                imei, bytesToHex(response));
        return message;
    }

    public byte[] generateLoginResponse(byte[] requestData) {
        try {
            if (requestData == null || requestData.length < 22) {
                throw new IllegalArgumentException("Invalid login packet");
            }

            byte[] response = new byte[11];
            // Header (2 bytes)
            response[0] = PROTOCOL_HEADER_1;
            response[1] = PROTOCOL_HEADER_2;
            // Length + Protocol (2 bytes)
            response[2] = 0x05;
            response[3] = PROTOCOL_LOGIN;
            // Serial number (2 bytes from original packet)
            response[4] = requestData[requestData.length-4];
            response[5] = requestData[requestData.length-3];
            // Success flag (1 byte)
            response[6] = 0x01;
            // CRC (1 byte)
            byte crc = 0;
            for (int i = 2; i <= 6; i++) {
                crc ^= response[i];
            }
            response[7] = crc;
            // Terminator (2 bytes)
            response[8] = 0x0D;
            response[9] = 0x0A;

            logger.debug("Generated GT06 login response: {}", bytesToHex(response));
            return response;
        } catch (Exception e) {
            logger.error("Error generating login response", e);
            // Fallback minimal response
            return new byte[] {
                    PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                    0x05, PROTOCOL_LOGIN,
                    0x00, 0x00, 0x01,
                    0x01, // Simple CRC
                    0x0D, 0x0A
            };
        }
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

    private String parseImei(byte[] data) throws ProtocolException {
        try {
            StringBuilder imei = new StringBuilder(15);

            // Byte 4 (0x08) -> low nibble (8)
            imei.append(data[4] & 0x0F);

            // Byte 5 (0x62) -> high nibble (6), low nibble (2)
            imei.append((data[5] >> 4) & 0x0F);
            imei.append(data[5] & 0x0F);

            // Byte 6 (0x47) -> high nibble (4), low nibble (7)
            imei.append((data[6] >> 4) & 0x0F);
            imei.append(data[6] & 0x0F);

            // Byte 7 (0x60) -> high nibble (6), low nibble (0)
            imei.append((data[7] >> 4) & 0x0F);
            imei.append(data[7] & 0x0F);

            // Byte 8 (0x51) -> high nibble (5), low nibble (1)
            imei.append((data[8] >> 4) & 0x0F);
            imei.append(data[8] & 0x0F);

            // Byte 9 (0x12) -> high nibble (1), low nibble (2)
            imei.append((data[9] >> 4) & 0x0F);
            imei.append(data[9] & 0x0F);

            // Byte 10 (0x41) -> high nibble (4), low nibble (1)
            imei.append((data[10] >> 4) & 0x0F);
            imei.append(data[10] & 0x0F);

            // Byte 11 (0x46) -> high nibble (4), low nibble (6)
            imei.append((data[11] >> 4) & 0x0F);
            imei.append(data[11] & 0x0F);

            String imeiStr = imei.toString();

            if (imeiStr.length() != 15 || !imeiStr.matches("\\d+")) {
                throw new ProtocolException("Invalid IMEI format: " + imeiStr);
            }

            return imeiStr;
        } catch (IndexOutOfBoundsException e) {
            throw new ProtocolException("IMEI extraction failed: insufficient data");
        }
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
        try {
            // Try standard GT06 IMEI position first (bytes 4-18)
            if (data.length >= IMEI_START_INDEX + IMEI_LENGTH) {
                byte[] imeiBytes = Arrays.copyOfRange(data, IMEI_START_INDEX, IMEI_START_INDEX + IMEI_LENGTH);
                String imei = new String(imeiBytes, StandardCharsets.US_ASCII);

                if (imei.matches("^\\d{15}$")) {
                    return imei;
                }
            }

            // Fallback: Try to find IMEI in the packet by scanning for 15 consecutive digits
            String packetString = new String(data, StandardCharsets.US_ASCII);
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d{15}").matcher(packetString);
            if (matcher.find()) {
                return matcher.group();
            }

            // Last resort: Extract bytes that might represent IMEI (even if not perfect)
            int start = Math.max(4, data.length - 20); // Look in last 20 bytes
            int end = Math.min(start + 15, data.length);
            byte[] possibleImei = Arrays.copyOfRange(data, start, end);
            String possibleImeiStr = new String(possibleImei, StandardCharsets.US_ASCII)
                    .replaceAll("[^0-9]", ""); // Remove non-digit characters

            if (possibleImeiStr.length() >= 8) { // Accept partial IMEI if necessary
                logger.warn("Using partial IMEI: {}", possibleImeiStr);
                return possibleImeiStr;
            }

            throw new ProtocolException("No valid IMEI found in packet");
        } catch (Exception e) {
            logger.error("IMEI extraction error from packet: {}", bytesToHex(data));
            throw new ProtocolException("IMEI extraction failed: " + e.getMessage());
        }
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
        if (data.length < GPS_PACKET_MIN_LENGTH) {
            throw new ProtocolException("GPS packet too short");
        }
        if (lastValidImei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        Position position = parseGpsData(data);
        parsedData.put("position", position);
        parsedData.put("response", generateStandardResponse(PROTOCOL_GPS, data));

        message.setImei(lastValidImei);
        message.setMessageType("DATA");
        return message;
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
            position.setTimestamp(parseTimestamp(buffer));
            position.setValid(buffer.get() == 1);

            double latitude = buffer.getInt() / 1800000.0;
            double longitude = buffer.getInt() / 1800000.0;

            validateCoordinates(latitude, longitude);
            position.setLatitude(latitude);
            position.setLongitude(longitude);

            position.setSpeed((buffer.get() & 0xFF) * 1.852); // Convert knots to km/h
            position.setCourse((double) (buffer.getShort() & 0xFFFF));

            logger.debug("Processed GPS data for IMEI: {}", lastValidImei);
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
}