package com.assettrack.iot.protocol;

import com.assettrack.iot.config.Checksum;
import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final int MIN_PACKET_LENGTH = 12;
    private static final int LOGIN_PACKET_LENGTH = 22;
    private static final long SESSION_TIMEOUT_MS = 30000; // 30 seconds

    private String lastValidImei;

    private final Map<String, DeviceSession> activeSessions = new ConcurrentHashMap<>();

    private static class DeviceSession {
        private final String imei;
        private final short lastSerialNumber;
        private long lastActivityTime;

        public DeviceSession(String imei, short serialNumber) {
            this.imei = imei;
            this.lastSerialNumber = serialNumber;
            this.lastActivityTime = System.currentTimeMillis();
        }

        public void updateActivity() {
            this.lastActivityTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - lastActivityTime > SESSION_TIMEOUT_MS;
        }
    }


    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("GT06");
        Map<String, Object> parsedData = new HashMap<>();
        message.setParsedData(parsedData);

        try {
            logger.debug("Raw input packet ({} bytes): {}", data.length, bytesToHex(data));

            validatePacket(data);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Skip header (0x78 0x78)
            buffer.position(2);
            int length = buffer.get() & 0xFF;
            byte protocol = buffer.get();

            logger.debug("Processing protocol: 0x{}, length: {}",
                    String.format("%02X", protocol), length);

            switch (protocol) {
                case PROTOCOL_LOGIN:
                    return handleLogin(buffer, message, parsedData);
                case PROTOCOL_GPS:
                    return handleGps(buffer, message, parsedData);
                case PROTOCOL_HEARTBEAT:
                    return handleHeartbeat(buffer, message, parsedData);
                case PROTOCOL_ALARM:
                    return handleAlarm(buffer, message, parsedData);
                default:
                    throw new ProtocolException("Unsupported GT06 protocol type: " + protocol);
            }
        } catch (Exception e) {
            logger.error("GT06 processing error for packet: {}", bytesToHex(data), e);
            message.setMessageType("ERROR");
            message.setError(e.getMessage());
            parsedData.put("response", generateErrorResponse(e));
            return message;
        }
    }

    private void validatePacket(byte[] data) throws ProtocolException {
        if (data.length < MIN_PACKET_LENGTH) {
            throw new ProtocolException(String.format(
                    "Packet too short (%d bytes), minimum required %d",
                    data.length, MIN_PACKET_LENGTH));
        }

        // Verify header
        if (data[0] != PROTOCOL_HEADER_1 || data[1] != PROTOCOL_HEADER_2) {
            throw new ProtocolException(String.format(
                    "Invalid protocol header: 0x%02X 0x%02X (expected 0x78 0x78)",
                    data[0], data[1]));
        }

        // Verify checksum using CRC-16/X25
        int receivedChecksum = ((data[data.length - 4] & 0xFF) << 8) | (data[data.length - 3] & 0xFF);
        ByteBuffer checksumBuffer = ByteBuffer.wrap(data, 2, data.length - 6);
        int calculatedChecksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        logger.debug("Checksum verification - Received: 0x{}, Calculated: 0x{}",
                Integer.toHexString(receivedChecksum), Integer.toHexString(calculatedChecksum));

        if (receivedChecksum != calculatedChecksum) {
            logger.warn("Checksum mismatch for packet portion: {}",
                    bytesToHex(Arrays.copyOfRange(data, 2, data.length - 4)));
            throw new ProtocolException(String.format(
                    "Checksum mismatch (received: 0x%04X, calculated: 0x%04X)",
                    receivedChecksum, calculatedChecksum));
        }

        // Verify termination bytes
        if (data[data.length - 2] != 0x0D || data[data.length - 1] != 0x0A) {
            throw new ProtocolException(String.format(
                    "Invalid packet termination: 0x%02X 0x%02X (expected 0x0D 0x0A)",
                    data[data.length - 2], data[data.length - 1]));
        }
    }

    private DeviceMessage handleLogin(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        try {
            // Extract IMEI (8 bytes after message type)
            byte[] imeiBytes = new byte[8];
            buffer.get(imeiBytes);
            String imei = extractImei(imeiBytes);

            logger.info("Login request from IMEI: {}", imei);
            logger.debug("IMEI bytes: {}", bytesToHex(imeiBytes));

            // Extract serial number (2 bytes before checksum)
            short serialNumber = buffer.getShort();
            logger.debug("Serial number: {}", serialNumber);

            // Check if this is a duplicate login from the same device
            DeviceSession session = activeSessions.get(imei);
            if (session != null && !session.isExpired()) {
                logger.debug("Existing active session found for IMEI: {}", imei);
                if (session.lastSerialNumber == serialNumber) {
                    logger.warn("Duplicate login packet with same serial number from IMEI: {}", imei);
                }
            }

            // Create or update session
            activeSessions.put(imei, new DeviceSession(imei, serialNumber));

            // Generate response
            byte[] response = generateLoginResponse(serialNumber);
            parsedData.put("response", response);
            logger.debug("Login response: {}", bytesToHex(response));

            message.setImei(imei);
            message.setMessageType("LOGIN");
            return message;
        } catch (Exception e) {
            throw new ProtocolException("Login processing failed: " + e.getMessage());
        }
    }

    private String extractImei(byte[] imeiBytes) throws ProtocolException {
        // Convert each byte to 2-digit decimal representation
        StringBuilder imei = new StringBuilder();
        for (byte b : imeiBytes) {
            int high = (b >> 4) & 0x0F;
            int low = b & 0x0F;
            imei.append(high).append(low);
        }

        // Special handling for GT06 IMEI format
        String imeiStr = imei.toString();
        if (imeiStr.length() == 16 && imeiStr.startsWith("08")) {
            // Remove leading zero (converts 086247605112414 to 862476051124146)
            imeiStr = imeiStr.substring(1);
        }

        if (imeiStr.length() != 15 || !imeiStr.matches("^\\d{15}$")) {
            throw new ProtocolException("Invalid IMEI format. Expected 15 digits, got: " + imeiStr);
        }

        return imeiStr;
    }

    public byte[] generateLoginResponse(short serialNumber) {
        byte[] response = new byte[10];
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x05; // Length
        response[3] = PROTOCOL_LOGIN;
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber);

        // Calculate checksum using CRC-16/X25
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 4);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[6] = (byte)(checksum >> 8);
        response[7] = (byte)(checksum);
        response[8] = 0x0D;
        response[9] = 0x0A;

        return response;
    }

    private byte[] generateErrorResponse(Exception error) {
        return new byte[] {
                PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                0x05, (byte)0x7F,
                0x00, 0x00, getErrorCode(error),
                0x0D, 0x0A
        };
    }

    private byte getErrorCode(Exception error) {
        String message = error.getMessage();
        if (message.contains("IMEI")) return 0x01;
        if (message.contains("checksum")) return 0x02;
        return (byte)0xFF;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    @Scheduled(fixedRate = 60000) // Cleanup every minute
    public void cleanupExpiredSessions() {
        activeSessions.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                logger.debug("Removing expired session for IMEI: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }










    @Override
    public boolean supports(String protocolType) {
        return "GT06".equalsIgnoreCase(protocolType);
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        // Handle GT06 protocol with any version or specific versions if needed
        return "GT06".equalsIgnoreCase(protocol);
    }
    private DeviceMessage handleGps(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        if (lastValidImei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        Position position = parseGpsData(buffer);
        parsedData.put("position", position);
        parsedData.put("response", generateGpsResponse());

        message.setImei(lastValidImei);
        message.setMessageType("GPS");
        return message;
    }

    private Position parseGpsData(ByteBuffer buffer) throws ProtocolException {
        Position position = new Position();
        Device device = new Device();
        device.setImei(lastValidImei);
        device.setProtocolType("GT06");
        position.setDevice(device);

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
    }

    private byte[] generateGpsResponse() {
        return new byte[] {
                PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                0x05, PROTOCOL_GPS,
                0x00, 0x00, 0x01, // Serial and success
                0x0D, 0x0A
        };
    }

    private DeviceMessage handleHeartbeat(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        if (lastValidImei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        byte[] response = generateHeartbeatResponse();
        parsedData.put("response", response);

        message.setImei(lastValidImei);
        message.setMessageType("HEARTBEAT");
        return message;
    }

    private byte[] generateHeartbeatResponse() {
        return new byte[] {
                PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                0x05, PROTOCOL_HEARTBEAT,
                0x00, 0x00, 0x01, // Serial and success
                0x0D, 0x0A
        };
    }

    private DeviceMessage handleAlarm(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        if (lastValidImei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        Position position = parseGpsData(buffer);
        position.setAlarmType(extractAlarmType(buffer));

        byte[] response = generateAlarmResponse();
        parsedData.put("response", response);
        parsedData.put("position", position);

        message.setImei(lastValidImei);
        message.setMessageType("ALARM");
        return message;
    }

    private String extractAlarmType(ByteBuffer buffer) {
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

    private byte[] generateAlarmResponse() {
        return new byte[] {
                PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                0x05, PROTOCOL_ALARM,
                0x00, 0x00, 0x01, // Serial and success
                0x0D, 0x0A
        };
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
                return parseGpsData(buffer);
            }
        } catch (Exception e) {
            logger.error("Error parsing position", e);
        }
        return null;
    }

    @Override
    public byte[] generateResponse(Position position) {
        return generateStandardResponse(PROTOCOL_LOGIN);
    }

    private byte[] generateStandardResponse(byte protocol) {
        return new byte[] {
                PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                0x05, protocol,
                0x00, 0x00, 0x01,
                0x0D, 0x0A
        };
    }
}