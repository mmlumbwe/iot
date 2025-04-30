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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class Gt06Handler implements ProtocolHandler {
    private static final Logger logger = LoggerFactory.getLogger(Gt06Handler.class);

    // Protocol constants
    private static final byte PROTOCOL_HEADER_1 = 0x78;
    private static final byte PROTOCOL_HEADER_2 = 0x78;
    private static final byte PROTOCOL_GPS = 0x12;
    private static final byte PROTOCOL_LOGIN = 0x01;  // Corrected to standard GT06 login type
    private static final byte PROTOCOL_HEARTBEAT = 0x13;
    private static final byte PROTOCOL_ALARM = 0x16;
    private static final int IMEI_LENGTH = 15;
    private String lastValidImei;  // Track IMEI between packets

    @Override
    public Position parsePosition(byte[] rawMessage) {
        if (rawMessage == null || rawMessage.length < 12) {  // Minimum valid packet size
            logger.warn("Invalid message length: {}",
                    rawMessage == null ? "null" : rawMessage.length);
            return null;
        }

        // Verify protocol markers
        if (rawMessage[0] != PROTOCOL_HEADER_1 || rawMessage[1] != PROTOCOL_HEADER_2) {
            logger.warn("Invalid protocol header");
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(rawMessage).order(ByteOrder.BIG_ENDIAN);
        buffer.get(); // Skip header byte 1
        buffer.get(); // Skip header byte 2

        try {
            int length = buffer.get() & 0xFF;
            byte protocol = buffer.get();

            logger.debug("Processing GT06 packet - Type: 0x{}, Length: {}",
                    Integer.toHexString(protocol & 0xFF), length);

            switch (protocol) {
                case PROTOCOL_LOGIN:
                    return handleLoginPacket(buffer);
                case PROTOCOL_GPS:
                    return parseGpsPacket(buffer);
                case PROTOCOL_HEARTBEAT:
                    return handleHeartbeat(buffer);
                case PROTOCOL_ALARM:
                    return parseAlarmData(buffer);
                default:
                    logger.warn("Unsupported protocol type: 0x{}",
                            Integer.toHexString(protocol & 0xFF));
                    return null;
            }
        } catch (Exception e) {
            logger.error("Error parsing GT06 message: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("GT06");

        try {
            // Extract IMEI first since it's common to all message types
            String imei = extractImei(data);
            message.setImei(imei);

            switch (data[3] & 0xFF) {
                case 0x01: // Login packet
                    message.setMessageType("LOGIN");
                    message.addParsedData("response", createLoginResponse(data));
                    message.addParsedData("device_info", extractDeviceInfo(data));
                    break;

                case 0x12: // GPS data packet
                    message.setMessageType("LOCATION");
                    Position position = extractPosition(data);
                    message.addParsedData("position", position);
                    message.addParsedData("response", createDataAcknowledgement(data));
                    break;

                case 0x13: // Heartbeat
                    message.setMessageType("HEARTBEAT");
                    message.addParsedData("response", createHeartbeatResponse(data));
                    message.addParsedData("status", extractStatusInfo(data));
                    break;

                case 0x16: // Alarm packet
                    message.setMessageType("ALARM");
                    Position alarmPosition = extractPosition(data);
                    message.addParsedData("position", alarmPosition);
                    //message.addParsedData("alarm_type", extractAlarmType(data));
                    message.addParsedData("response", createDataAcknowledgement(data));
                    break;

                default:
                    throw new ProtocolException("Unsupported GT06 protocol number: " + (data[3] & 0xFF));
            }

            return message;
        } catch (Exception e) {
            throw new ProtocolException("Failed to process GT06 packet: " + e.getMessage(), e);
        }
    }

    private void handleLoginPacket(DeviceMessage message, byte[] data) throws ProtocolException {
        if (data.length != 28) {
            throw new ProtocolException("Invalid login packet length");
        }
        message.addParsedData("response", createLoginResponse(data));
        message.addParsedData("device_info", extractDeviceInfo(data));
    }

    private Map<String, Object> extractDeviceInfo(byte[] data) {
        Map<String, Object> info = new HashMap<>();
        // Byte 12: Terminal information
        info.put("language", (data[12] & 0x80) != 0 ? "Chinese" : "English");
        info.put("timezone", data[12] & 0x7F);

        // Bytes 13-16: Firmware version (BCD encoded)
        StringBuilder version = new StringBuilder();
        for (int i = 13; i <= 16; i++) {
            version.append(String.format("%02X", data[i] & 0xFF));
            if (i < 16) version.append(".");
        }
        info.put("firmware", version.toString());

        return info;
    }

    private byte[] createLoginResponse(byte[] requestData) {
        return new byte[] {
                0x78, 0x78,                     // Start bytes
                0x05,                           // Length (5 bytes of content)
                0x01,                           // Protocol number (login response)
                requestData[requestData.length-4], // Serial number MSB (echo)
                requestData[requestData.length-3], // Serial number LSB (echo)
                0x00, 0x00, 0x00, 0x00,        // Empty
                0x01,                           // Information serial number
                0x0D, 0x0A                     // Stop bytes
        };
    }

    private void handleHeartbeat(DeviceMessage message, byte[] data) {
        message.addParsedData("response", createHeartbeatResponse(data));
        message.addParsedData("status", extractStatusInfo(data));
    }

    private Map<String, Object> extractStatusInfo(byte[] data) {
        Map<String, Object> status = new HashMap<>();
        // Byte 12: Status flags
        status.put("gps_fixed", (data[12] & 0x01) != 0);
        status.put("charging", (data[12] & 0x02) != 0);
        status.put("alarm", (data[12] & 0x04) != 0);
        status.put("armed", (data[12] & 0x08) != 0);

        // Byte 13: Battery level (0-100%)
        status.put("battery", data[13] & 0xFF);

        // Byte 14: GSM signal (0-5 bars)
        status.put("gsm_signal", data[14] & 0x0F);

        return status;
    }

    private byte[] createHeartbeatResponse(byte[] requestData) {
        return new byte[] {
                0x78, 0x78,                     // Start bytes
                0x05,                           // Length
                0x13,                           // Protocol number (heartbeat response)
                requestData[requestData.length-4], // Serial number MSB (echo)
                requestData[requestData.length-3], // Serial number LSB (echo)
                0x00, 0x00, 0x00, 0x00,        // Empty
                0x01,                           // Information serial number
                0x0D, 0x0A                     // Stop bytes
        };
    }

    private Position extractPosition(byte[] data) {
        Position position = new Position();
        //position.setImei(extractImei(data));
        position.setTimestamp(extractGpsTime(data));
        position.setLatitude(extractLatitude(data));
        position.setLongitude(extractLongitude(data));
        position.setSpeed(extractSpeed(data));
        position.setCourse(extractCourse(data));
        position.setValid(isGpsValid(data));

        // Additional metrics
        //position.set("satellites", extractSatelliteCount(data));
        //position.set("battery", extractBatteryLevel(data));

        // Add status flags if available
        if (data.length > 30) {
            //position.set("charging", (data[30] & 0x02) != 0);
            //position.set("alarm", (data[30] & 0x04) != 0);
        }

        return position;
    }

    private int extractBatteryLevel(byte[] data) {
        // For data packets, battery is in byte 31 (0-100%)
        if (data.length > 31) {
            return data[31] & 0xFF;
        }
        // For status packets, check status extraction instead
        return -1; // Unknown
    }

    private int extractSatelliteCount(byte[] data) {
        // Byte 29: Number of satellites (0-12)
        return data[29] & 0x0F;
    }

    private boolean isGpsValid(byte[] data) {
        // Check GPS fix flag (bit 0 of byte 30)
        return (data[30] & 0x01) != 0;
    }

    private double extractCourse(byte[] data) {
        // Bytes 27-28: Course (0-359 degrees)
        return ((data[27] & 0xFF) << 8) | (data[28] & 0xFF);
    }

    private double extractSpeed(byte[] data) {
        // Byte 26: Speed in knots (0-255)
        return (data[26] & 0xFF) * 1.852; // Convert to km/h
    }

    private double extractLongitude(byte[] data) {
        // Bytes 22-25: 4 bytes longitude (similar to latitude)
        int degrees = ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
        double minutes = ((data[24] & 0xFF) << 8) | (data[25] & 0xFF);
        minutes /= 10000.0;

        double longitude = degrees + minutes / 60.0;
        // Check East/West flag (bit 0 of byte 30)
        if ((data[30] & 0x01) == 0) {
            longitude = -longitude; // West is negative
        }
        return longitude;
    }

    private byte[] createDataAcknowledgement(byte[] data) {
        return new byte[] {
                0x78, 0x78,                 // Start bytes
                0x05,                       // Length
                data[3],                    // Protocol number (echo)
                data[data.length-4],        // Serial number MSB (echo)
                data[data.length-3],        // Serial number LSB (echo)
                0x00, 0x00, 0x00, 0x00,    // Empty
                0x01,                       // Information serial number
                0x0D, 0x0A                  // Stop bytes
        };
    }

    // Helper extraction methods
    private String extractImei(byte[] data) {
        StringBuilder imei = new StringBuilder();
        // Bytes 4-10 contain first 14 digits
        for (int i = 4; i <= 10; i++) {
            imei.append((data[i] >> 4) & 0x0F);
            imei.append(data[i] & 0x0F);
        }
        // Byte 11 contains last digit
        imei.append((data[11] >> 4) & 0x0F);
        return imei.toString();
    }

    private LocalDateTime extractGpsTime(byte[] data) {
        // Bytes 12-17: YY MM DD HH mm SS
        int year = 2000 + (data[12] & 0xFF);
        int month = data[13] & 0xFF; // Month in LocalDateTime is 1-based
        int day = data[14] & 0xFF;
        int hour = data[15] & 0xFF;
        int minute = data[16] & 0xFF;
        int second = data[17] & 0xFF;

        return LocalDateTime.of(year, month, day, hour, minute, second);
    }

    private double extractLatitude(byte[] data) {
        // Bytes 18-21: 4 bytes latitude
        int degrees = ((data[18] & 0xFF) << 8) | (data[19] & 0xFF);
        double minutes = ((data[20] & 0xFF) << 8) | (data[21] & 0xFF);
        minutes /= 10000.0;
        return degrees + minutes / 60.0;
    }

// Additional extraction methods for longitude, speed, course etc...

    private String extractCleanImei(ByteBuffer buffer) {
        byte[] imeiBytes = new byte[IMEI_LENGTH];
        buffer.get(imeiBytes);

        // Direct ASCII conversion (no special chars)
        String imei = new String(imeiBytes, StandardCharsets.US_ASCII);
        logger.info("IMEI OBTAINED: {}",imei);
        // Verify all characters are digits
        if (!imei.matches("^\\d{15}$")) {
            logger.error("Invalid IMEI format: {}", bytesToHex(imeiBytes));
            throw new ProtocolException("IMEI contains non-digit characters");
        }

        return imei;
    }

    private Position handleLoginPacket(ByteBuffer buffer) {
        Position position = new Position();
        Device device = new Device();

        try {
            this.lastValidImei = extractCleanImei(buffer);
            device.setImei(lastValidImei);
            device.setName("GT06-" + lastValidImei);
            device.setProtocolType("GT06");

            position.setDevice(device);
            position.setValid(false);

            logger.info("Processed GT06 login for IMEI: {}", lastValidImei);
            return position;
        } catch (Exception e) {
            logger.error("Login packet error: {}", e.getMessage());
            return null;
        }
    }

    private Position parseGpsPacket(ByteBuffer buffer) {
        if (lastValidImei == null) {
            logger.warn("Received GPS packet without prior login");
            return null;
        }

        Position position = new Position();
        Device device = new Device();
        device.setImei(lastValidImei);
        device.setName("GT06-" + lastValidImei);
        device.setProtocolType("GT06");

        try {
            // Parse date/time (YYMMDDHHMMSS)
            int year = 2000 + (buffer.get() & 0xFF);
            int month = buffer.get() & 0xFF;
            int day = buffer.get() & 0xFF;
            int hour = buffer.get() & 0xFF;
            int minute = buffer.get() & 0xFF;
            int second = buffer.get() & 0xFF;

            // GPS validity
            boolean valid = (buffer.get() & 0xFF) == 1;  // 1 = valid, 0 = invalid
            position.setValid(valid);

            // Parse coordinates (degrees * 1800000)
            position.setLatitude(buffer.getInt() / 1800000.0);
            position.setLongitude(buffer.getInt() / 1800000.0);

            // Parse speed (knots → km/h) and course
            position.setSpeed((buffer.get() & 0xFF) * 1.852);
            position.setCourse((double) (buffer.getShort() & 0xFFFF));

            // Create timestamp
            @SuppressWarnings("deprecation")
            LocalDateTime timestamp = LocalDateTime.of(year, month + 1, day, hour, minute, second);
            position.setTimestamp(timestamp);
            position.setDevice(device);

            logger.debug("Parsed GPS data for IMEI {}: {}", lastValidImei, position);
            return position;
        } catch (Exception e) {
            logger.error("GPS parsing error: {}", e.getMessage());
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private Position handleHeartbeat(ByteBuffer buffer) {
        Position position = new Position();
        Device device = new Device();

        device.setImei("GT06-HB");
        device.setName("GT06-Heartbeat");
        device.setProtocolType("GT06");
        position.setDevice(device);
        position.setValid(false);

        return position;
    }

    private Position parseAlarmData(ByteBuffer buffer) {
        Position position = parseGpsPacket(buffer);
        if (position != null) {
            int alarmType = buffer.get() & 0xFF;
            position.setAlarmType(convertAlarmType(alarmType));
        }
        return position;
    }

    private String convertAlarmType(int alarmType) {
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
        ByteBuffer buffer = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);

        // Standard GT06 response
        buffer.put(PROTOCOL_HEADER_1);
        buffer.put(PROTOCOL_HEADER_2);
        buffer.put((byte)0x05); // Length
        buffer.put((byte)0x01); // Type (login response)
        buffer.put((byte)0x00); // Response ID high byte
        buffer.put((byte)0x01); // Response ID low byte

        // Calculate checksum
        byte checksum = 0;
        for (int i = 2; i < 6; i++) {
            checksum ^= buffer.array()[i];
        }
        buffer.put(checksum);

        // End bytes
        buffer.put((byte)0x0D);
        buffer.put((byte)0x0A);

        return buffer.array();
    }

    @Override
    public boolean supports(String protocolType) {
        return "GT06".equalsIgnoreCase(protocolType);
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        return supports(protocol);
    }
}