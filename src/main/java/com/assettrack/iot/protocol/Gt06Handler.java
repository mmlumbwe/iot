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

    private static final byte PROTOCOL_LOGIN_EXT = 0x11;
    private static final byte PROTOCOL_GPS_EXT = 0x22;
    private static final byte PROTOCOL_ALARM_EXT = 0x26;

    private static final int IMEI_LENGTH = 15;
    private String lastValidImei;

    // Packet structure constants
    private static final int MIN_PACKET_LENGTH = 12;
    private static final int HEADER_LENGTH = 2;
    private static final int CHECKSUM_LENGTH = 2;
    private static final int LOGIN_PACKET_MIN_LENGTH = 22;
    private static final int IMEI_START_INDEX = 4;
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
            validateBasicPacketStructure(data);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            buffer.position(2); // Skip header

            byte protocol = buffer.get();
            parsedData.put("keepAlive", protocol != 0x7F);

            switch (protocol) {
                case PROTOCOL_LOGIN:
                case PROTOCOL_LOGIN_EXT:
                    return handleLogin(data, message.getRemoteAddress(), parsedData);
                case PROTOCOL_GPS:
                    return handleGps(data, message, parsedData);
                case PROTOCOL_HEARTBEAT:
                    return handleHeartbeat(data, message, parsedData);
                case PROTOCOL_ALARM:
                    return handleAlarm(data, message, parsedData);
                default:
                    throw new ProtocolException("Unsupported protocol type: 0x" + String.format("%02X", protocol));
            }
        } catch (ProtocolException e) {
            logger.warn("Protocol error: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Handler error", e);
            throw new ProtocolException("Internal error: " + e.getMessage());
        }
    }

    private DeviceMessage handleLogin(byte[] data, SocketAddress remoteAddress, Map<String, Object> parsedData)
            throws ProtocolException {
        try {
            validateLoginPacket(data);
            String imei = extractImeiFromPacket(data);

            this.lastValidImei = imei;
            parsedData.put("imei", imei);

            DeviceMessage message = new DeviceMessage();
            message.setImei(imei);
            message.setProtocol("GT06");
            message.setMessageType("LOGIN");
            message.setRemoteAddress(remoteAddress);
            message.setParsedData(parsedData);
            message.addParsedData("response", createLoginResponse(data));

            return message;
        } catch (Exception e) {
            logger.error("IMEI extraction failed for packet: {}", bytesToHex(data));
            throw new ProtocolException("IMEI extraction failed: " + e.getMessage());
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

    private byte[] createLoginResponse(byte[] loginPacket) {
        byte[] response = new byte[11];
        System.arraycopy(START_BYTES, 0, response, 0, 2);
        response[2] = 0x05; // Length
        response[3] = PROTOCOL_LOGIN;
        response[4] = LOGIN_RESPONSE_SUCCESS;
        response[5] = loginPacket[loginPacket.length-4]; // Serial
        response[6] = loginPacket[loginPacket.length-3]; // Serial

        // Calculate CRC
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
    private void validateLoginPacket(byte[] data) throws ProtocolException {
        if (data.length < 12) { // Minimum possible login packet size
            throw new ProtocolException("Packet too short for login (minimum 12 bytes)");
        }
        if (data[0] != PROTOCOL_HEADER_1 || data[1] != PROTOCOL_HEADER_2) {
            throw new ProtocolException("Invalid protocol header");
        }
        // Accept either standard (0x01) or extended (0x11) login protocol byte
        if (data[2] != PROTOCOL_LOGIN && data[2] != PROTOCOL_LOGIN_EXT) {
            throw new ProtocolException("Not a login packet (protocol byte: 0x" +
                    String.format("%02X", data[2]) + ")");
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
        if (bytes == null) return "null";
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
            throw new ProtocolException("Invalid message length");
        }
        if (data[0] != PROTOCOL_HEADER_1 || data[1] != PROTOCOL_HEADER_2) {
            throw new ProtocolException("Invalid protocol header");
        }
        // Don't validate checksum for login packets to be more lenient
        if (data[2] != PROTOCOL_LOGIN && data[2] != PROTOCOL_LOGIN_EXT &&
                data.length < HEADER_LENGTH + CHECKSUM_LENGTH) {
            throw new ProtocolException("Message too short for checksum");
        }
    }

    public enum ValidationMode {
        STRICT, LENIENT, RECOVER
    }
}