package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
    private static final int IMEI_LENGTH = 15;
    private String lastValidImei;

    private static final int MAX_MINUTE = 59;
    private static final int MAX_HOUR = 23;

    private static final int LOGIN_PACKET_LENGTH = 17; // Minimum login packet size
    private static final int GPS_PACKET_LENGTH = 35;   // Minimum GPS packet size
    private static final int HEADER_LENGTH = 2;        // 0x78 0x78
    private static final int CHECKSUM_LENGTH = 2;      // Checksum bytes

    // GT06 Protocol Constants
    private static final int MIN_PACKET_LENGTH = 12;
    private static final int LOGIN_PACKET_EXPECTED_LENGTH = 17;
    private static final int GPS_PACKET_EXPECTED_LENGTH = 35;
    private static final int HEARTBEAT_LENGTH = 12;
    private static final int ALARM_PACKET_LENGTH = 35;

    // Validation configuration (add with other constants)
    public enum ValidationMode {
        STRICT,    // Reject invalid values
        LENIENT,   // Clamp to nearest valid
        RECOVER    // Attempt to recover sensible values
    }

    @Value("${gt06.validation.mode:STRICT}")
    private ValidationMode validationMode;

    @Value("${gt06.validation.hour.mode:}")
    private Optional<ValidationMode> hourValidationMode;

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
            // 1. Basic validation
            validateBasicPacketStructure(data);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // 2. Parse headers and validate length
            buffer.position(2); // Skip 0x78 0x78
            int declaredLength = buffer.get() & 0xFF;
            byte protocol = buffer.get();
            int actualLength = data.length - HEADER_LENGTH - CHECKSUM_LENGTH;

            // 3. Protocol-specific validation
            switch (protocol) {
                case PROTOCOL_LOGIN:
                    if (data.length < LOGIN_PACKET_LENGTH) {
                        throw new ProtocolException("Login packet too short");
                    }
                    return handleLogin(data, message, parsedData);

                case PROTOCOL_GPS:
                    if (data.length < GPS_PACKET_LENGTH) {
                        throw new ProtocolException("GPS packet too short");
                    }
                    return handleGps(data, message, parsedData);

                case PROTOCOL_HEARTBEAT:
                    if (data.length < HEARTBEAT_LENGTH) {
                        throw new ProtocolException("Heartbeat packet too short");
                    }
                    return handleHeartbeat(data, message, parsedData);

                case PROTOCOL_ALARM:
                    if (data.length < ALARM_PACKET_LENGTH) {
                        throw new ProtocolException("Alarm packet too short");
                    }
                    return handleAlarm(data, message, parsedData);

                default:
                    if (declaredLength != actualLength) {
                        throw new ProtocolException(String.format(
                                "Length mismatch (declared: %d, actual: %d)",
                                declaredLength, actualLength));
                    }
                    throw new ProtocolException("Unsupported protocol type: 0x" +
                            String.format("%02X", protocol));
            }
        } catch (ProtocolException e) {
            String currentImei = lastValidImei != null ? lastValidImei : "UNKNOWN";
            logger.warn("Protocol error for IMEI {}: {}", currentImei, e.getMessage());
            message.setMessageType("ERROR");
            message.setError(e.getMessage());
            parsedData.put("response", generateErrorResponse(data, e));
            return message;
        } catch (Exception e) {
            message.setMessageType("ERROR");
            message.setError("Internal server error: " + e.getMessage());
            parsedData.put("response", generateErrorResponse(data, e));
            return message;
        }
    }

    private byte[] generateErrorResponse(byte[] requestData, Exception error) {
        try {
            byte[] serial = extractSerialNumber(requestData);
            byte errorCode = getErrorCode(error);
            String errorMsg = error.getMessage();

            // Special handling for coordinate errors
            if (errorMsg.contains("longitude") || errorMsg.contains("latitude")) {
                logger.warn("Attempting coordinate recovery for IMEI {}", lastValidImei);
                errorCode = (byte) 0x81; // Special code for recoverable coordinate error
            }

            return ByteBuffer.allocate(13)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(PROTOCOL_HEADER_1)
                    .put(PROTOCOL_HEADER_2)
                    .put((byte) 0x05)
                    .put((byte) 0x7F) // Error protocol
                    .put(serial[0])
                    .put(serial[1])
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x00).put((byte) 0x00)
                    .put(errorCode)
                    .put((byte) 0x0D).put((byte) 0x0A)
                    .array();
        } catch (Exception e) {
            logger.error("Failed to generate error response", e);
            return generateFallbackResponse((byte) 0x7F);
        }
    }

    private byte getErrorCode(Exception error) {
        String message = error.getMessage();
        if (message.contains("minute")) return 0x02;
        if (message.contains("hour")) return 0x01;
        if (message.contains("second")) return 0x03;
        return (byte) 0xFF; // Unknown error
    }

    /**
     * Extracts the serial number from a GT06 protocol packet
     * @param data The raw packet data
     * @return 2-byte array containing serial number
     * @throws ProtocolException if packet is too short
     */
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

    private byte determineErrorCode(Exception error) {
        if (error.getMessage().contains("hour")) return 0x01;
        if (error.getMessage().contains("minute")) return 0x02;
        if (error.getMessage().contains("second")) return 0x03;
        return (byte) 0xFF; // Generic error
    }

    private void validateBasicPacketStructure(byte[] data) throws ProtocolException {
        // Basic null and minimum length check
        if (data == null || data.length < MIN_PACKET_LENGTH) {
            throw new ProtocolException("Invalid message length");
        }

        // Protocol header check
        if (data[0] != PROTOCOL_HEADER_1 || data[1] != PROTOCOL_HEADER_2) {
            throw new ProtocolException("Invalid protocol header");
        }

        // Verify checksum exists
        if (data.length < HEADER_LENGTH + CHECKSUM_LENGTH) {
            throw new ProtocolException("Message too short for checksum");
        }
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

        logger.info("Login request from IMEI: {}", imei);

        byte[] response = generateLoginResponse(data);
        parsedData.put("response", response);
        parsedData.put("device_info", extractDeviceInfo(data));

        logger.info("Generated login response for IMEI: {} ({} bytes)", imei, response.length);
        return message;
    }

    private byte[] generateLoginResponse(byte[] requestData) {
        try {
            if (requestData == null || requestData.length < LOGIN_PACKET_LENGTH) {
                throw new ProtocolException("Invalid login packet");
            }

            return ByteBuffer.allocate(13)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(PROTOCOL_HEADER_1)
                    .put(PROTOCOL_HEADER_2)
                    .put((byte) 0x05) // Length (13 - 4 = 9? Verify protocol specs)
                    .put(PROTOCOL_LOGIN)
                    .put(requestData[requestData.length-4]) // Serial number
                    .put(requestData[requestData.length-3])
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x01) // Success
                    .put((byte) 0x0D).put((byte) 0x0A)
                    .array();
        } catch (Exception e) {
            logger.error("Login response generation failed", e);
            return generateFallbackResponse(PROTOCOL_LOGIN);
        }
    }

    private DeviceMessage handleGps(byte[] data, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        if (data.length < GPS_PACKET_LENGTH) {
            throw new ProtocolException("GPS packet too short");
        }

        if (lastValidImei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        message.setImei(lastValidImei);
        message.setMessageType("LOCATION");

        logger.info("Processing GPS data for IMEI: {}", lastValidImei);

        Position position = parseGpsData(data);
        parsedData.put("position", position);
        parsedData.put("response", generateGpsResponse(data));

        logger.info("Processed location update for IMEI: {} - Lat: {}, Lon: {}",
                lastValidImei, position.getLatitude(), position.getLongitude());

        return message;
    }

    private byte[] generateGpsResponse(byte[] data) {
        try {
            if (data == null || data.length < 8) {
                throw new IllegalArgumentException("Invalid GPS data");
            }

            return ByteBuffer.allocate(13)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(PROTOCOL_HEADER_1)
                    .put(PROTOCOL_HEADER_2)
                    .put((byte) 0x05) // Length
                    .put(PROTOCOL_GPS)
                    .put(data[data.length-4]) // Serial
                    .put(data[data.length-3])
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x01) // Success
                    .put((byte) 0x0D).put((byte) 0x0A) // End bytes
                    .array();
        } catch (Exception e) {
            logger.error("Failed to generate GPS response", e);
            return new byte[] {
                    PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                    0x05, PROTOCOL_GPS,
                    0x00, 0x00, // Default serial
                    0x00, 0x00, 0x00, 0x00,
                    0x01, 0x0D, 0x0A
            };
        }
    }
    private DeviceMessage handleHeartbeat(byte[] data, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        if (data.length < 12) {
            throw new ProtocolException("Heartbeat packet too short");
        }

        message.setImei(lastValidImei != null ? lastValidImei : "UNKNOWN");
        message.setMessageType("HEARTBEAT");

        // Generate and store response
        byte[] response = generateHeartbeatResponse(data);
        parsedData.put("response", response);
        parsedData.put("status", extractStatusInfo(data));

        logger.debug("Heartbeat response generated: {} bytes", response.length);
        return message;
    }

    private byte[] generateHeartbeatResponse(byte[] data) {
        try {
            if (data == null || data.length < 8) {
                throw new IllegalArgumentException("Invalid heartbeat data");
            }

            return ByteBuffer.allocate(13)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(PROTOCOL_HEADER_1)
                    .put(PROTOCOL_HEADER_2)
                    .put((byte) 0x05) // Length
                    .put(PROTOCOL_HEARTBEAT)
                    .put(data[data.length-4]) // Serial
                    .put(data[data.length-3])
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x01) // Success
                    .put((byte) 0x0D).put((byte) 0x0A) // End bytes
                    .array();
        } catch (Exception e) {
            logger.error("Failed to generate heartbeat response", e);
            return new byte[] {
                    PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                    0x05, PROTOCOL_HEARTBEAT,
                    0x00, 0x00, // Default serial
                    0x00, 0x00, 0x00, 0x00,
                    0x01, 0x0D, 0x0A
            };
        }
    }

    private DeviceMessage handleAlarm(byte[] data, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        if (data.length < 35) {
            throw new ProtocolException("Alarm packet too short");
        }

        if (lastValidImei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        message.setImei(lastValidImei);
        message.setMessageType("ALARM");

        // Parse position and set alarm type
        Position position = parseGpsData(data);
        position.setAlarmType(extractAlarmType(data));

        // Generate and store response
        byte[] response = generateAlarmResponse(data);
        parsedData.put("response", response);
        parsedData.put("position", position);

        logger.debug("Alarm response generated: {} bytes", response.length);
        return message;
    }

    private byte[] generateAlarmResponse(byte[] data) {
        try {
            if (data == null || data.length < 8) {
                throw new IllegalArgumentException("Invalid alarm data");
            }

            return ByteBuffer.allocate(13)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(PROTOCOL_HEADER_1)
                    .put(PROTOCOL_HEADER_2)
                    .put((byte) 0x05) // Length
                    .put(PROTOCOL_ALARM)
                    .put(data[data.length-4]) // Serial
                    .put(data[data.length-3])
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x01) // Success
                    .put((byte) 0x0D).put((byte) 0x0A) // End bytes
                    .array();
        } catch (Exception e) {
            logger.error("Failed to generate alarm response", e);
            return new byte[] {
                    PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                    0x05, PROTOCOL_ALARM,
                    0x00, 0x00, // Default serial
                    0x00, 0x00, 0x00, 0x00,
                    0x01, 0x0D, 0x0A
            };
        }
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
            buffer.position(3); // Skip header and length

            byte protocol = buffer.get();
            if (protocol == PROTOCOL_GPS || protocol == PROTOCOL_ALARM) {
                return parseGpsData(rawMessage);
            }
        } catch (Exception e) {
            logger.error("Error parsing position: {}", e.getMessage());
        }
        return null;
    }

    private Position parseGpsData(byte[] data) throws ProtocolException {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buffer.position(4); // Skip header and length

        Position position = new Position();
        Device device = new Device();
        device.setImei(lastValidImei);
        device.setProtocolType("GT06");
        position.setDevice(device);

        try {
            // Parse timestamp
            position.setTimestamp(parseTimestamp(buffer));

            // Parse GPS info
            position.setValid(buffer.get() == 1);

            // Parse coordinates with enhanced validation
            double latitude = buffer.getInt() / 1800000.0;
            double longitude = buffer.getInt() / 1800000.0;

            try {
                validateCoordinates(latitude, longitude);
            } catch (ProtocolException e) {
                logger.warn("Coordinate validation failed for IMEI {}: {}",
                        lastValidImei, e.getMessage());
                throw e;
            }

            // Normalize coordinates
            double normalizedLat = normalizedLatitude(latitude);
            double normalizedLon = normalizedLongitude(longitude);

            position.setLatitude(normalizedLat);
            position.setLongitude(normalizedLon);

            // Parse speed and course
            position.setSpeed((buffer.get() & 0xFF) * 1.852); // Knots to km/h
            position.setCourse((double) (buffer.getShort() & 0xFFFF));

            logger.info("Processed location update for IMEI: {} - Lat: {}, Lon: {}",
                    lastValidImei, normalizedLat, normalizedLon);

            return position;
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("Incomplete GPS data packet");
        }
    }

    /**
     * Normalizes latitude to ensure it's within valid range (-90 to 90)
     * @param latitude Raw latitude value
     * @return Normalized latitude value
     * @throws ProtocolException if latitude cannot be normalized to valid range
     */
    private double normalizedLatitude(double latitude) throws ProtocolException {
        // Check for NaN
        if (Double.isNaN(latitude)) {
            throw new ProtocolException("Latitude is not a number");
        }

        // Check for zero coordinates (common error)
        if (latitude == 0.0) {
            logger.warn("Zero latitude value from IMEI {}", lastValidImei);
            throw new ProtocolException("Invalid zero latitude");
        }

        // Check if already in valid range
        if (latitude >= -90 && latitude <= 90) {
            return latitude;
        }

        // Attempt to normalize (for cases like 91° which might mean 89°)
        double normalized = latitude;
        if (latitude > 90) {
            normalized = 90 - (latitude - 90);
        } else if (latitude < -90) {
            normalized = -90 + (-90 - latitude);
        }

        // Verify normalization worked
        if (normalized >= -90 && normalized <= 90) {
            logger.warn("Normalized latitude from {} to {}", latitude, normalized);
            return normalized;
        }

        throw new ProtocolException(
                String.format("Invalid latitude: %.6f (valid range -90 to 90)", latitude));
    }

    /**
     * Normalizes longitude to ensure it's within valid range (-180 to 180)
     * @param longitude Raw longitude value
     * @return Normalized longitude value
     * @throws ProtocolException if longitude cannot be normalized to valid range
     */
    private double normalizedLongitude(double longitude) throws ProtocolException {
        // Check for NaN
        if (Double.isNaN(longitude)) {
            throw new ProtocolException("Longitude is not a number");
        }

        // Check for zero coordinates (common error)
        if (longitude == 0.0) {
            logger.warn("Zero longitude value from IMEI {}", lastValidImei);
            throw new ProtocolException("Invalid zero longitude");
        }

        // Check if already in valid range
        if (longitude >= -180 && longitude <= 180) {
            return longitude;
        }

        // Normalize longitude to -180..180 range
        double normalized = ((longitude + 180) % 360 + 360) % 360 - 180;

        // Handle edge case where normalization might result in -180
        if (normalized == -180) {
            normalized = 180;
        }

        logger.warn("Normalized longitude from {} to {}", longitude, normalized);
        return normalized;
    }

    private int validateRange(int value, int min, int max, String field)
            throws ProtocolException {
        if (value < min || value > max) {
            throw new ProtocolException(
                    String.format("Invalid %s value: %d (valid range %d-%d)",
                            field, value, min, max));
        }
        return value;
    }

    private LocalDateTime parseTimestamp(ByteBuffer buffer) throws ProtocolException {
        try {
            int year = 2000 + (buffer.get() & 0xFF);
            int month = validateRange(buffer.get() & 0xFF, 1, 12, "month");
            int day = validateRange(buffer.get() & 0xFF, 1, 31, "day");

            // Handle hour with original value preservation
            int originalHour = buffer.get() & 0xFF;
            int hour = originalHour;
            ValidationMode hourMode = getModeForField("hour");
            if (hourMode == ValidationMode.STRICT && (hour < 0 || hour > 23)) {
                throw new ProtocolException("Invalid hour value: " + hour);
            } else if (hourMode == ValidationMode.LENIENT) {
                hour = Math.min(23, Math.max(0, hour));
                if (hour != originalHour) {
                    logger.warn("Adjusted hour from {} to {}", originalHour, hour);
                }
            }

            // Handle minute with original value preservation
            int originalMinute = buffer.get() & 0xFF;
            int minute = originalMinute;
            ValidationMode minuteMode = getModeForField("minute");
            if (minuteMode == ValidationMode.STRICT && (minute < 0 || minute > 59)) {
                throw new ProtocolException("Invalid minute value: " + minute);
            } else if (minuteMode == ValidationMode.LENIENT) {
                minute = Math.min(59, Math.max(0, minute));
                if (minute != originalMinute) {
                    logger.warn("Adjusted minute from {} to {}", originalMinute, minute);
                }
            }

            int second = validateRange(buffer.get() & 0xFF, 0, 59, "second");

            return LocalDateTime.of(year, month, day, hour, minute, second);
        } catch (DateTimeException e) {
            throw new ProtocolException("Invalid timestamp: " + e.getMessage());
        }
    }

    private void validateCoordinates(double latitude, double longitude) throws ProtocolException {
        // Check for "zero" coordinates (common error)
        if (latitude == 0.0 && longitude == 0.0) {
            throw new ProtocolException("Invalid zero coordinates");
        }

        // Validate latitude range
        if (Double.isNaN(latitude) || latitude < -90 || latitude > 90) {
            throw new ProtocolException(
                    String.format("Invalid latitude: %.6f (valid range -90 to 90)", latitude));
        }

        // Enhanced longitude validation with recovery suggestions
        if (Double.isNaN(longitude)) {
            throw new ProtocolException("Longitude is not a number");
        }

        if (longitude < -180 || longitude > 180) {
            // Try to normalize longitude that's just slightly out of range
            double normalized = normalizeLongitude(longitude);
            if (normalized >= -180 && normalized <= 180) {
                logger.warn("Normalized longitude from {} to {}", longitude, normalized);
                longitude = normalized;
            } else {
                throw new ProtocolException(
                        String.format("Invalid longitude: %.6f (valid range -180 to 180)", longitude));
            }
        }
    }

    private double normalizeLongitude(double longitude) {
        // Handle common cases where longitude is in 0-360 range
        if (longitude > 180 && longitude <= 360) {
            return longitude - 360;
        }
        // Handle wrapped longitudes
        return ((longitude + 180) % 360) - 180;
    }

    private String extractAlarmType(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buffer.position(35); // Position where alarm type is stored
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

    @Override
    public byte[] generateResponse(Position position) {
        ByteBuffer buffer = ByteBuffer.allocate(11)
                .order(ByteOrder.BIG_ENDIAN)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte) 0x05) // Length
                .put(PROTOCOL_LOGIN)
                .put((byte) 0x00).put((byte) 0x01) // Serial number
                .put((byte) 0xD9) // Checksum
                .put((byte) 0x0D).put((byte) 0x0A); // End bytes

        return buffer.array();
    }

    private String parseImei(byte[] data) throws ProtocolException {
        try {
            // Some devices send IMEI as ASCII characters
            if (data.length >= 17 && Character.isDigit(data[2])) {
                String imeiStr = new String(data, 2, 15, StandardCharsets.US_ASCII);
                if (imeiStr.matches("^\\d{15}$")) {
                    logger.info("Extracted ASCII IMEI: {}", imeiStr);
                    return imeiStr;
                }
            }

            // Fall back to binary extraction
            StringBuilder imei = new StringBuilder(15);
            for (int i = 2; i <= 9; i++) {
                byte b = data[i];
                imei.append((b >> 4) & 0x0F);
                if (imei.length() < 15) {
                    imei.append(b & 0x0F);
                }
            }

            String imeiStr = imei.toString();
            if (imeiStr.length() != 15 || !imeiStr.matches("^\\d{15}$")) {
                throw new ProtocolException("Invalid IMEI format: " + imeiStr);
            }

            logger.info("Extracted binary IMEI: {}", imeiStr);
            return imeiStr;
        } catch (Exception e) {
            throw new ProtocolException("IMEI extraction failed: " + e.getMessage());
        }
    }

    private Map<String, Object> extractDeviceInfo(byte[] data) {
        Map<String, Object> info = new HashMap<>();
        info.put("language", (data[12] & 0x80) != 0 ? "Chinese" : "English");
        info.put("timezone", data[12] & 0x7F);

        StringBuilder version = new StringBuilder();
        for (int i = 13; i <= 16; i++) {
            version.append(String.format("%02X", data[i] & 0xFF));
            if (i < 16) version.append(".");
        }
        info.put("firmware", version.toString());
        return info;
    }

    private Map<String, Object> extractStatusInfo(byte[] data) {
        Map<String, Object> status = new HashMap<>();
        status.put("gps_fixed", (data[12] & 0x01) != 0);
        status.put("charging", (data[12] & 0x02) != 0);
        status.put("alarm", (data[12] & 0x04) != 0);
        status.put("armed", (data[12] & 0x08) != 0);
        status.put("battery", data[13] & 0xFF);
        status.put("gsm_signal", data[14] & 0x0F);
        return status;
    }

    private byte[] createLoginResponse(byte[] requestData) {
        try {
            if (requestData == null || requestData.length < 4) {
                throw new ProtocolException("Invalid request data");
            }

            ByteBuffer buffer = ByteBuffer.allocate(13)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(PROTOCOL_HEADER_1)
                    .put(PROTOCOL_HEADER_2)
                    .put((byte) 0x05) // Length
                    .put(PROTOCOL_LOGIN)
                    .put(requestData[requestData.length-4]) // Serial number
                    .put(requestData[requestData.length-3])
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x01)
                    .put((byte) 0x0D).put((byte) 0x0A); // End bytes

            return buffer.array();
        } catch (Exception e) {
            logger.error("Login response creation failed", e);
            return null;
        }
    }

    private byte[] createHeartbeatResponse(byte[] requestData) {
        ByteBuffer buffer = ByteBuffer.allocate(13)
                .order(ByteOrder.BIG_ENDIAN)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte) 0x05) // Length
                .put(PROTOCOL_HEARTBEAT)
                .put(requestData[requestData.length-4]) // Serial number
                .put(requestData[requestData.length-3])
                .put((byte) 0x00).put((byte) 0x00).put((byte) 0x00).put((byte) 0x00)
                .put((byte) 0x01)
                .put((byte) 0x0D).put((byte) 0x0A); // End bytes

        return buffer.array();
    }

    private byte[] createDataAcknowledgement(byte[] data) throws ProtocolException {
        if (data == null || data.length < 8) {
            throw new ProtocolException("Invalid data for acknowledgement");
        }

        ByteBuffer buffer = ByteBuffer.allocate(13)
                .order(ByteOrder.BIG_ENDIAN)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte) 0x05) // Length
                .put(data[3]) // Protocol type from original message
                .put(data[data.length-4]) // Serial number
                .put(data[data.length-3])
                .put((byte) 0x00).put((byte) 0x00).put((byte) 0x00).put((byte) 0x00)
                .put((byte) 0x01)
                .put((byte) 0x0D).put((byte) 0x0A); // End bytes

        return buffer.array();
    }

    /**
     * Generates a fallback response when normal response generation fails
     * @param protocolType The protocol type (LOGIN, GPS, HEARTBEAT, ALARM)
     * @return A valid GT06 protocol response packet (13 bytes)
     */
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

    // Helper method to get mode for specific field
    private ValidationMode getModeForField(String field) {
        return switch (field.toLowerCase()) {
            case "hour" -> hourValidationMode.orElse(validationMode);
            default -> validationMode;
        };
    }
}