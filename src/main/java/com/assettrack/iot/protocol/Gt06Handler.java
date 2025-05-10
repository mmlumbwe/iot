package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
    private static final byte PROTOCOL_STATUS = 0x23;

    private static final int MIN_PACKET_LENGTH = 12;
    private static final int LOGIN_PACKET_LENGTH = 22;
    private static final int GPS_PACKET_MIN_LENGTH = 35;
    private static final int ALARM_PACKET_MIN_LENGTH = 35;
    private static final int HEARTBEAT_PACKET_LENGTH = 12;

    private String lastValidImei;

    @Override
    public boolean supports(String protocolType) {
        return "GT06".equalsIgnoreCase(protocolType);
    }



    @Override
    public boolean canHandle(String protocol, String version) {
        // Handle GT06 protocol with any version or specific versions if needed
        return "GT06".equalsIgnoreCase(protocol);
    }

    private boolean verifyChecksum(byte[] data) {
        int receivedChecksum = ((data[data.length - 4] & 0xFF) << 8) | (data[data.length - 3] & 0xFF);
        int calculatedChecksum = calculateGt06Checksum(data);
        return receivedChecksum == calculatedChecksum;
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

        // Correct checksum verification
        int receivedChecksum = ((data[data.length - 4] & 0xFF) << 8) | (data[data.length - 3] & 0xFF);
        int calculatedChecksum = calculateGt06Checksum(data);

        logger.debug("Checksum verification - Received: 0x{}, Calculated: 0x{}",
                Integer.toHexString(receivedChecksum), Integer.toHexString(calculatedChecksum));

        if (receivedChecksum != calculatedChecksum) {
            // Log the problematic portion of the packet
            int checksumStart = 2;
            int checksumEnd = data.length - 4;
            logger.warn("Checksum mismatch for packet portion: {}",
                    bytesToHex(Arrays.copyOfRange(data, checksumStart, checksumEnd)));

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

    /**
     * Correct GT06 checksum calculation (XOR of all bytes between header and checksum)
     */
    private int calculateGt06Checksum(byte[] data) {
        int checksum = 0;
        // Calculate from byte 2 (after header) to byte length-4 (before checksum)
        for (int i = 2; i < data.length - 4; i++) {
            checksum ^= (data[i] & 0xFF);
        }
        return checksum;
    }

    private DeviceMessage handleLogin(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData)
            throws ProtocolException {
        try {
            // Extract IMEI (8 bytes after message type)
            byte[] imeiBytes = new byte[8];
            buffer.get(imeiBytes);
            String imei = extractImei(imeiBytes);
            lastValidImei = imei;

            logger.info("Login request from IMEI: {}", imei);
            logger.debug("IMEI bytes: {}", bytesToHex(imeiBytes));

            // Extract serial number (2 bytes before checksum)
            short serialNumber = buffer.getShort();
            logger.debug("Serial number: {}", serialNumber);

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

    public byte[] generateLoginResponse(short serialNumber) {
        byte[] response = new byte[10];
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x05; // Length
        response[3] = PROTOCOL_LOGIN;
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber);

        // Calculate checksum (XOR of bytes 2-5)
        int checksum = 0;
        for (int i = 2; i <= 5; i++) {
            checksum ^= (response[i] & 0xFF);
        }

        response[6] = (byte)(checksum >> 8);
        response[7] = (byte)(checksum);
        response[8] = 0x0D;
        response[9] = 0x0A;

        return response;
    }

    // Helper method to convert byte array to hex string
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private String extractImei(byte[] imeiBytes) throws ProtocolException {
        StringBuilder imei = new StringBuilder();
        for (byte b : imeiBytes) {
            int high = (b >> 4) & 0x0F;
            int low = b & 0x0F;
            imei.append(high).append(low);
        }
        // Trim to 15 digits
        String imeiStr = imei.toString().substring(0, 15);

        if (!imeiStr.matches("^\\d{15}$")) {
            throw new ProtocolException("Invalid IMEI format");
        }
        return imeiStr;
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
        if (message.contains("minute")) return 0x02;
        if (message.contains("hour")) return 0x01;
        if (message.contains("second")) return 0x03;
        return (byte)0xFF;
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