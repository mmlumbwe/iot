package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.model.session.DeviceSession;
import com.assettrack.iot.service.session.SessionManager;
import org.apache.commons.codec.binary.Hex;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.Arrays;
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
    private static final byte PROTOCOL_DATA = 0x10;
    private static final int IMEI_LENGTH = 15;
    private String lastValidImei;
    private Socket socket;

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

    private static final byte LOGIN_RESPONSE_SUCCESS = 0x01;
    private static final byte[] START_BYTES = new byte[] {0x78, 0x78};
    private static final int LOGIN_PACKET_MIN_LENGTH = 22;
    private static final int IMEI_START_INDEX = 4;

    // Validation configuration
    public enum ValidationMode {
        STRICT,    // Reject invalid values
        LENIENT,   // Clamp to nearest valid
        RECOVER    // Attempt to recover sensible values
    }

    @Value("${gt06.validation.mode:STRICT}")
    private ValidationMode validationMode;

    @Value("${gt06.validation.hour.mode:}")
    private Optional<ValidationMode> hourValidationMode;

    @Autowired
    private SessionManager sessionManager;

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
        if (socket == null) {
            throw new ProtocolException("Socket connection not available");
        }
        return handle(data, socket);
    }

    public DeviceMessage handle(byte[] data, Socket socket) throws ProtocolException {
        this.socket = socket;
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("GT06");
        Map<String, Object> parsedData = new HashMap<>();
        message.setParsedData(parsedData);

        try {
            validateBasicPacketStructure(data);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            buffer.position(2); // Skip header

            int declaredLength = buffer.get() & 0xFF;
            byte protocol = buffer.get();

            parsedData.put("keepAlive", protocol != 0x7F);
            int actualLength = data.length - HEADER_LENGTH - CHECKSUM_LENGTH;

            switch (protocol) {
                case PROTOCOL_LOGIN:
                    message = handleLogin(data, socket.getRemoteSocketAddress(), parsedData);
                    break;
                case PROTOCOL_GPS:
                    message = handleGps(data, message, parsedData);
                    break;
                case PROTOCOL_HEARTBEAT:
                    message = handleHeartbeat(data, message, parsedData);
                    break;
                case PROTOCOL_ALARM:
                    message = handleAlarm(data, message, parsedData);
                    break;
                default:
                    throw new ProtocolException("Unsupported protocol type: 0x" +
                            String.format("%02X", protocol));
            }

            if (!parsedData.containsKey("response")) {
                byte[] response = generateResponseForProtocol(protocol, data);
                parsedData.put("response", response);
            }

            if (protocol == PROTOCOL_LOGIN || protocol == PROTOCOL_GPS || protocol == PROTOCOL_HEARTBEAT) {
                parsedData.put("keepAlive", true);
            }

            if (protocol == PROTOCOL_HEARTBEAT) {
                parsedData.put("isHeartbeat", true);
                logger.debug("Heartbeat received from IMEI: {}", lastValidImei);
            }

            message.setParsedData(parsedData);
            return message;
        } catch (ProtocolException e) {
            logger.warn("Protocol error: {}", e.getMessage());
            parsedData.put("response", generateErrorResponse(data, e));
            message.setError(e.getMessage());
            return message;
        } catch (Exception e) {
            logger.error("Handler error", e);
            parsedData.put("response", generateFallbackResponse(PROTOCOL_LOGIN));
            message.setError("Internal error: " + e.getMessage());
            return message;
        }
    }

    private byte[] generateResponseForProtocol(byte protocol, byte[] data) {
        switch (protocol) {
            case PROTOCOL_LOGIN: return generateLoginResponse(data);
            case PROTOCOL_GPS: return generateGpsResponse(data);
            case PROTOCOL_HEARTBEAT: return generateHeartbeatResponse(data);
            case PROTOCOL_ALARM: return generateAlarmResponse(data);
            default: return generateFallbackResponse(protocol);
        }
    }

    private byte[] generateErrorResponse(byte[] requestData, Exception error) {
        try {
            byte[] serial = extractSerialNumber(requestData);
            byte errorCode = getErrorCode(error);
            String errorMsg = error.getMessage();

            if (errorMsg.contains("longitude") || errorMsg.contains("latitude")) {
                logger.warn("Attempting coordinate recovery for IMEI {}", lastValidImei);
                errorCode = (byte) 0x81;
            }

            return ByteBuffer.allocate(13)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(PROTOCOL_HEADER_1)
                    .put(PROTOCOL_HEADER_2)
                    .put((byte) 0x05)
                    .put((byte) 0x7F)
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
        return (byte) 0xFF;
    }

    private byte[] extractSerialNumber(byte[] data) throws ProtocolException {
        if (data == null || data.length < 4) {
            throw new ProtocolException("Packet too short for serial number extraction");
        }

        return new byte[] {
                data[data.length - 4],
                data[data.length - 3]
        };
    }

    private byte determineErrorCode(Exception error) {
        if (error.getMessage().contains("hour")) return 0x01;
        if (error.getMessage().contains("minute")) return 0x02;
        if (error.getMessage().contains("second")) return 0x03;
        return (byte) 0xFF;
    }

    private void validateBasicPacketStructure(byte[] data) throws ProtocolException {
        if (data == null || data.length < MIN_PACKET_LENGTH) {
            throw new ProtocolException("Invalid message length");
        }

        if (data[0] != PROTOCOL_HEADER_1 || data[1] != PROTOCOL_HEADER_2) {
            throw new ProtocolException("Invalid protocol header");
        }

        if (data.length < HEADER_LENGTH + CHECKSUM_LENGTH) {
            throw new ProtocolException("Message too short for checksum");
        }
    }

    public DeviceMessage handleLogin(byte[] data, SocketAddress remoteAddress, Map<String, Object> parsedData) {
        String imei = extractImei(data);
        if (imei == null) {
            throw new ProtocolException("Invalid login packet - IMEI extraction failed");
        }

        this.lastValidImei = imei;
        parsedData.put("imei", imei);

        byte[] response = createLoginResponse(data);

        DeviceMessage message = new DeviceMessage();
        message.setImei(imei);
        message.setProtocol("GT06");
        message.setRawData(data);
        message.setResponse(response);
        message.setRemoteAddress(remoteAddress);
        message.setParsedData(parsedData);

        return message;
    }

    public DeviceMessage handleMessage(byte[] data, SocketAddress remoteAddress) {
        Map<String, Object> parsedData = new HashMap<>();
        DeviceMessage message = new DeviceMessage();

        if (data.length >= 3 && data[2] == 0x01) {
            message = handleLogin(data, remoteAddress, parsedData);
        } else if (data.length >= 3 && data[2] == 0x12) {
            message = handleGpsData(data, remoteAddress, parsedData);
        }

        return message;
    }

    public byte[] createLoginResponse(byte[] loginPacket) {
        if (loginPacket == null || loginPacket.length < LOGIN_PACKET_MIN_LENGTH) {
            throw new IllegalArgumentException("Invalid login packet");
        }

        byte[] response = new byte[11];
        System.arraycopy(START_BYTES, 0, response, 0, 2);
        response[2] = 0x05;
        response[3] = 0x01;
        response[4] = LOGIN_RESPONSE_SUCCESS;
        response[5] = loginPacket[loginPacket.length - 4];
        response[6] = loginPacket[loginPacket.length - 3];

        byte crc = 0;
        for (int i = 2; i <= 6; i++) {
            crc ^= response[i];
        }
        response[7] = crc;

        response[8] = 0x0D;
        response[9] = 0x0A;

        logger.debug("Created login response: {}", bytesToHex(response));
        return response;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    public String extractImei(byte[] data) {
        if (data == null || data.length < LOGIN_PACKET_MIN_LENGTH) {
            logger.warn("Invalid login packet length: {}", data != null ? data.length : "null");
            return null;
        }

        if (data[0] != START_BYTES[0] || data[1] != START_BYTES[1]) {
            logger.warn("Invalid packet start bytes");
            return null;
        }

        if (data[2] != 0x01) {
            logger.warn("Not a login packet, type: {}", String.format("%02X", data[2]));
            return null;
        }

        try {
            byte[] imeiBytes = Arrays.copyOfRange(data, IMEI_START_INDEX, IMEI_START_INDEX + IMEI_LENGTH);
            String imei = new String(imeiBytes, StandardCharsets.US_ASCII);

            if (!imei.matches("^\\d{15}$")) {
                logger.warn("Invalid IMEI format: {}", imei);
                return null;
            }

            logger.debug("Extracted IMEI: {}", imei);
            return imei;
        } catch (Exception e) {
            logger.error("IMEI extraction failed", e);
            return null;
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

    private DeviceMessage handleGpsData(byte[] data, SocketAddress remoteAddress, Map<String, Object> parsedData) {
        DeviceMessage message = new DeviceMessage();
        message.setImei(lastValidImei);
        message.setMessageType("LOCATION");

        try {
            Position position = parseGpsData(data);
            parsedData.put("position", position);
            parsedData.put("response", generateGpsResponse(data));
        } catch (ProtocolException e) {
            message.setError(e.getMessage());
        }

        return message;
    }

    private DeviceMessage handleHeartbeat(byte[] data, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        if (data.length < 12) {
            throw new ProtocolException("Heartbeat packet too short");
        }

        message.setImei(lastValidImei != null ? lastValidImei : "UNKNOWN");
        message.setMessageType("HEARTBEAT");

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
                    .put((byte) 0x05)
                    .put(PROTOCOL_HEARTBEAT)
                    .put(data[data.length-4])
                    .put(data[data.length-3])
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x01)
                    .put((byte) 0x0D).put((byte) 0x0A)
                    .array();
        } catch (Exception e) {
            logger.error("Failed to generate heartbeat response", e);
            return new byte[] {
                    PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                    0x05, PROTOCOL_HEARTBEAT,
                    0x00, 0x00,
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

        Position position = parseGpsData(data);
        position.setAlarmType(extractAlarmType(data));

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
                    .put((byte) 0x05)
                    .put(PROTOCOL_ALARM)
                    .put(data[data.length-4])
                    .put(data[data.length-3])
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x01)
                    .put((byte) 0x0D).put((byte) 0x0A)
                    .array();
        } catch (Exception e) {
            logger.error("Failed to generate alarm response", e);
            return new byte[] {
                    PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                    0x05, PROTOCOL_ALARM,
                    0x00, 0x00,
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
            buffer.position(3);

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
        if (lastValidImei == null) {
            throw new ProtocolException("No valid IMEI available for GPS data parsing");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buffer.position(4);

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

            double normalizedLat = normalizedLatitude(latitude);
            double normalizedLon = normalizedLongitude(longitude);

            position.setLatitude(normalizedLat);
            position.setLongitude(normalizedLon);

            position.setSpeed((buffer.get() & 0xFF) * 1.852);
            position.setCourse((double) (buffer.getShort() & 0xFFFF));

            logger.info("Processed location update for IMEI: {} - Lat: {}, Lon: {}",
                    lastValidImei, normalizedLat, normalizedLon);

            return position;
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("Incomplete GPS data packet");
        }
    }

    private double normalizedLatitude(double latitude) throws ProtocolException {
        if (Double.isNaN(latitude)) {
            throw new ProtocolException("Latitude is not a number");
        }

        if (latitude == 0.0) {
            logger.warn("Zero latitude value from IMEI {}", lastValidImei);
            throw new ProtocolException("Invalid zero latitude");
        }

        if (latitude >= -90 && latitude <= 90) {
            return latitude;
        }

        double normalized = latitude;
        if (latitude > 90) {
            normalized = 90 - (latitude - 90);
        } else if (latitude < -90) {
            normalized = -90 + (-90 - latitude);
        }

        if (normalized >= -90 && normalized <= 90) {
            logger.warn("Normalized latitude from {} to {}", latitude, normalized);
            return normalized;
        }

        throw new ProtocolException(
                String.format("Invalid latitude: %.6f (valid range -90 to 90)", latitude));
    }

    private double normalizedLongitude(double longitude) throws ProtocolException {
        if (Double.isNaN(longitude)) {
            throw new ProtocolException("Longitude is not a number");
        }

        if (longitude == 0.0) {
            logger.warn("Zero longitude value from IMEI {}", lastValidImei);
            throw new ProtocolException("Invalid zero longitude");
        }

        if (longitude >= -180 && longitude <= 180) {
            return longitude;
        }

        double normalized = ((longitude + 180) % 360 + 360) % 360 - 180;

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
        if (latitude == 0.0 && longitude == 0.0) {
            throw new ProtocolException("Invalid zero coordinates");
        }

        if (Double.isNaN(latitude) || latitude < -90 || latitude > 90) {
            throw new ProtocolException(
                    String.format("Invalid latitude: %.6f (valid range -90 to 90)", latitude));
        }

        if (Double.isNaN(longitude)) {
            throw new ProtocolException("Longitude is not a number");
        }

        if (longitude < -180 || longitude > 180) {
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
        if (longitude > 180 && longitude <= 360) {
            return longitude - 360;
        }
        return ((longitude + 180) % 360) - 180;
    }

    private String extractAlarmType(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        buffer.position(35);
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
                .put((byte) 0x05)
                .put(PROTOCOL_LOGIN)
                .put((byte) 0x00).put((byte) 0x01)
                .put((byte) 0xD9)
                .put((byte) 0x0D).put((byte) 0x0A);

        return buffer.array();
    }

    private String parseImei(byte[] data) throws ProtocolException {
        final int IMEI_START = 4;
        final int IMEI_END = 12;
        final int REQUIRED_IMEI_LENGTH = 15;

        try {
            if (data == null || data.length < IMEI_END) {
                throw new ProtocolException(String.format(
                        "Invalid packet length: %d bytes (requires at least %d)",
                        data != null ? data.length : 0, IMEI_END));
            }

            byte[] imeiBytes = Arrays.copyOfRange(data, IMEI_START, IMEI_END);
            String hexString = bytesToHex(imeiBytes);
            String imei = extractDigitsFromHex(hexString, 1);

            if (imei.length() == REQUIRED_IMEI_LENGTH) {
                logger.debug("Successfully extracted IMEI: {}", imei);
                return imei;
            }

            throw new ProtocolException(String.format(
                    "Extracted %d digits (needs %d) from hex: %s",
                    imei.length(), REQUIRED_IMEI_LENGTH, hexString));

        } catch (Exception e) {
            logger.error("IMEI extraction failed. Packet: {}",
                    data != null ? Hex.encodeHexString(data) : "null");
            throw new ProtocolException("IMEI extraction failed: " + e.getMessage());
        }
    }

    private String extractDigitsFromHex(String hexString, int startOffset) {
        StringBuilder digits = new StringBuilder();
        for (int i = startOffset; i < hexString.length(); i++) {
            char c = hexString.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        return digits.toString();
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

    private byte[] generateFallbackResponse(byte protocolType) {
        byte validProtocol = protocolType;
        if (protocolType != PROTOCOL_LOGIN &&
                protocolType != PROTOCOL_GPS &&
                protocolType != PROTOCOL_HEARTBEAT &&
                protocolType != PROTOCOL_ALARM) {
            validProtocol = PROTOCOL_LOGIN;
        }

        return ByteBuffer.allocate(13)
                .order(ByteOrder.BIG_ENDIAN)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte) 0x05)
                .put(validProtocol)
                .put((byte) 0x00)
                .put((byte) 0x00)
                .put((byte) 0x00)
                .put((byte) 0x00)
                .put((byte) 0x00)
                .put((byte) 0x00)
                .put((byte) 0x00)
                .put((byte) 0x0D)
                .put((byte) 0x0A)
                .array();
    }

    private byte[] generateFallbackResponse(byte protocolType, byte errorCode) {
        byte[] response = generateFallbackResponse(protocolType);
        if (response != null && response.length >= 12) {
            response[11] = errorCode;
        }
        return response;
    }

    private ValidationMode getModeForField(String field) {
        return switch (field.toLowerCase()) {
            case "hour" -> hourValidationMode.orElse(validationMode);
            default -> validationMode;
        };
    }

    private byte[] generateStandardResponse(byte protocol, byte[] requestData) {
        try {
            return ByteBuffer.allocate(13)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(PROTOCOL_HEADER_1)
                    .put(PROTOCOL_HEADER_2)
                    .put((byte) 0x05)
                    .put(protocol)
                    .put(requestData[requestData.length-4])
                    .put(requestData[requestData.length-3])
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x00).put((byte) 0x00)
                    .put((byte) 0x01)
                    .put((byte) 0x0D).put((byte) 0x0A)
                    .array();
        } catch (Exception e) {
            logger.error("Failed to generate response", e);
            return generateFallbackResponse(protocol);
        }
    }

    private byte[] generateLoginResponse(byte[] requestData) {
        return generateStandardResponse(PROTOCOL_LOGIN, requestData);
    }

    private byte[] generateGpsResponse(byte[] requestData) {
        return generateStandardResponse(PROTOCOL_GPS, requestData);
    }
}