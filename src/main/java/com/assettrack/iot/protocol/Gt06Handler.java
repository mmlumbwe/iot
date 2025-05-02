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
    private static final int IMEI_LENGTH = 15;
    private String lastValidImei;

    private static final int MAX_MINUTE = 59;
    private static final int MAX_HOUR = 23;

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

        try {
            validateBasicPacketStructure(data);

            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            buffer.position(2);

            int length = buffer.get() & 0xFF;
            byte protocol = buffer.get();

            if (length != data.length - 4) { // -4 for header and checksum
                throw new ProtocolException("Length mismatch");
            }

            switch (protocol) {
                case PROTOCOL_LOGIN: return handleLogin(data, message);
                case PROTOCOL_GPS: return handleGps(data, message);
                case PROTOCOL_HEARTBEAT: return handleHeartbeat(data, message);
                case PROTOCOL_ALARM: return handleAlarm(data, message);
                default: throw new ProtocolException("Unsupported protocol type");
            }
        } catch (ProtocolException e) {
            message.setMessageType("ERROR");
            //message.setError(e.getMessage());
            return message;
        }
    }

    private void validateBasicPacketStructure(byte[] data) throws ProtocolException {
        if (data == null || data.length < 5) {
            throw new ProtocolException("Invalid message length");
        }
        if (data[0] != PROTOCOL_HEADER_1 || data[1] != PROTOCOL_HEADER_2) {
            throw new ProtocolException("Invalid protocol header");
        }
    }

    private DeviceMessage handleLogin(byte[] data, DeviceMessage message) throws ProtocolException {
        if (data.length < 18) {
            throw new ProtocolException("Login packet too short");
        }

        String imei = parseImei(data);
        lastValidImei = imei;
        message.setImei(imei);
        message.setMessageType("LOGIN");

        Map<String, Object> response = new HashMap<>();
        response.put("response", createLoginResponse(data));
        response.put("device_info", extractDeviceInfo(data));
        message.setParsedData(response);

        return message;
    }

    private DeviceMessage handleGps(byte[] data, DeviceMessage message) throws ProtocolException {
        if (data.length < 35) {
            throw new ProtocolException("GPS packet too short");
        }

        if (lastValidImei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        message.setImei(lastValidImei);
        message.setMessageType("LOCATION");

        Map<String, Object> response = new HashMap<>();
        response.put("position", parseGpsData(data));
        response.put("response", createDataAcknowledgement(data));
        message.setParsedData(response);

        return message;
    }

    private DeviceMessage handleHeartbeat(byte[] data, DeviceMessage message) throws ProtocolException {
        if (data.length < 12) {
            throw new ProtocolException("Heartbeat packet too short");
        }

        message.setImei(lastValidImei != null ? lastValidImei : "UNKNOWN");
        message.setMessageType("HEARTBEAT");

        Map<String, Object> response = new HashMap<>();
        response.put("response", createHeartbeatResponse(data));
        response.put("status", extractStatusInfo(data));
        message.setParsedData(response);

        return message;
    }

    private DeviceMessage handleAlarm(byte[] data, DeviceMessage message) throws ProtocolException {
        if (data.length < 35) {
            throw new ProtocolException("Alarm packet too short");
        }

        if (lastValidImei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        message.setImei(lastValidImei);
        message.setMessageType("ALARM");

        Map<String, Object> response = new HashMap<>();
        Position position = parseGpsData(data);
        position.setAlarmType(extractAlarmType(data));
        response.put("position", position);
        response.put("response", createDataAcknowledgement(data));
        message.setParsedData(response);

        return message;
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
        buffer.position(4);

        Position position = new Position();
        Device device = new Device();
        device.setImei(lastValidImei != null ? lastValidImei : "UNKNOWN");
        device.setProtocolType("GT06");  // Ensure protocolType is set
        position.setDevice(device);

        // Parse and validate timestamp
        int year = 2000 + (buffer.get() & 0xFF);
        int month = validateRange(buffer.get() & 0xFF, 1, 12, "month");
        int day = validateRange(buffer.get() & 0xFF, 1, 31, "day");
        int hour = validateRange(buffer.get() & 0xFF, 0, 23, "hour");
        int minute = validateRange(buffer.get() & 0xFF, 0, 59, "minute");
        int second = validateRange(buffer.get() & 0xFF, 0, 59, "second");

        position.setTimestamp(LocalDateTime.of(year, month, day, hour, minute, second));

        // Parse GPS info with validation
        position.setValid(buffer.get() == 1);

        double latitude = buffer.getInt() / 1800000.0;
        double longitude = buffer.getInt() / 1800000.0;
        validateCoordinates(latitude, longitude);

        position.setLatitude(latitude);
        position.setLongitude(longitude);

        int speedKnots = buffer.get() & 0xFF;
        position.setSpeed(Math.min(speedKnots * 1.852, 1000)); // Cap at 1000 km/h

        position.setCourse((double) (buffer.getShort() & 0xFFFF) % 360); // Normalize course

        return position;
    }

    private int validateRange(int value, int min, int max, String field) throws ProtocolException {
        if (value < min || value > max) {
            throw new ProtocolException(
                    String.format("Invalid %s value: %d (valid range %d-%d)", field, value, min, max)
            );
        }
        return value;
    }

    private void validateCoordinates(double lat, double lon) throws ProtocolException {
        if (lat < -90 || lat > 90) {
            throw new ProtocolException("Invalid latitude: " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new ProtocolException("Invalid longitude: " + lon);
        }
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
        return new byte[] {
                PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                0x05, // Length
                0x01, // Login response
                0x00, 0x01, // Serial number
                (byte) 0xD9, // Checksum
                0x0D, 0x0A // End bytes
        };
    }

    private String parseImei(byte[] data) throws ProtocolException {
        try {
            StringBuilder imei = new StringBuilder();
            for (int i = 4; i <= 10; i++) {
                imei.append((data[i] >> 4) & 0x0F);
                imei.append(data[i] & 0x0F);
            }
            imei.append((data[11] >> 4) & 0x0F);

            String imeiStr = imei.toString();
            if (!imeiStr.matches("^\\d{15}$")) {
                throw new ProtocolException("Invalid IMEI format");
            }
            return imeiStr;
        } catch (IndexOutOfBoundsException e) {
            throw new ProtocolException("IMEI extraction failed: insufficient data");
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
        return new byte[] {
                PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                0x05, PROTOCOL_LOGIN,
                requestData[requestData.length-4], requestData[requestData.length-3],
                0x00, 0x00, 0x00, 0x00,
                0x01, 0x0D, 0x0A
        };
    }

    private byte[] createHeartbeatResponse(byte[] requestData) {
        return new byte[] {
                PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                0x05, PROTOCOL_HEARTBEAT,
                requestData[requestData.length-4], requestData[requestData.length-3],
                0x00, 0x00, 0x00, 0x00,
                0x01, 0x0D, 0x0A
        };
    }

    private byte[] createDataAcknowledgement(byte[] data) throws ProtocolException {
        if (data == null || data.length < 8) {
            throw new ProtocolException("Invalid data for acknowledgement");
        }

        return new byte[] {
                PROTOCOL_HEADER_1, PROTOCOL_HEADER_2,
                0x05, data[3],
                data[data.length-4], data[data.length-3],
                0x00, 0x00, 0x00, 0x00,
                0x01, 0x0D, 0x0A
        };
    }
}