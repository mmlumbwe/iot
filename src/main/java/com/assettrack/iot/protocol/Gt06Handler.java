package com.assettrack.iot.protocol;

import com.assettrack.iot.config.Checksum;
import com.assettrack.iot.handler.network.AcknowledgementHandler;
import com.assettrack.iot.model.session.DeviceSession;
import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final byte PROTOCOL_ERROR = 0x7F;
    private static final byte PROTOCOL_FOOTER = 0x0A;

    private static final int MIN_PACKET_LENGTH = 12;
    private static final int LOGIN_PACKET_LENGTH = 22;
    private static final long SESSION_TIMEOUT_MS = 300000; // 2 minutes

    private final AtomicReference<String> lastValidImei = new AtomicReference<>();
    private final Map<String, DeviceSession> activeSessions = new ConcurrentHashMap<>();

    // Add VL03-specific constants
    private static final byte VL03_PROTOCOL_EXTENDED = 0x26; // Example VL03-specific message type
    private static final byte VL03_ALARM_TYPE = (byte) 0xA2; // VL03-specific alarm code

    // Add variant detection
    private enum Variant {
        STANDARD,
        VL03,
        UNKNOWN
    }

    private Variant detectVariant(ByteBuffer buffer) {
        if (buffer.remaining() > 10) {
            byte protocol = buffer.get(buffer.position() + 3); // Protocol byte position
            if (protocol == VL03_PROTOCOL_EXTENDED) {
                return Variant.VL03;
            }
        }
        return Variant.STANDARD;
    }

    @Autowired
    private AcknowledgementHandler acknowledgementHandler;

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("GT06");
        Map<String, Object> parsedData = new HashMap<>();
        message.setParsedData(parsedData);

        try {
            logger.info("Raw input packet ({} bytes): {}", data.length, bytesToHex(data));

            validatePacket(data);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Skip header (0x78 0x78)
            buffer.position(2);
            int length = buffer.get() & 0xFF;
            byte protocol = buffer.get();

            logger.info("Processing protocol: 0x{}, length: {}",
                    String.format("%02X", protocol), length);

            // Notify acknowledgement handler of received packet
            acknowledgementHandler.write(null, new AcknowledgementHandler.EventReceived(), null);

            Variant variant = detectVariant(ByteBuffer.wrap(data));
            logger.info("Detected device variant: {}", variant);

            switch (protocol) {
                case PROTOCOL_LOGIN:
                    return handleLogin(buffer, message, parsedData);
                case PROTOCOL_GPS:
                    return handleGps(buffer, message, parsedData);
                case VL03_PROTOCOL_EXTENDED: // VL03-specific handling
                    return handleVl03Extended(buffer, message, parsedData);
                case PROTOCOL_HEARTBEAT:
                    return handleHeartbeat(buffer, message, parsedData);
                case PROTOCOL_ALARM:
                    return handleAlarm(buffer, message, parsedData);
                default:
                    throw new ProtocolException("Unsupported GT06 protocol type: " + protocol);
            }
        } catch (Exception e) {
            logger.error("GT06 processing error", e);
            message.setError(e.getMessage());

            // Generate error response
            byte[] errorResponse = generateErrorResponse(e);
            message.setResponseData(errorResponse);
            message.setResponseRequired(true);
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

        // Verify length matches actual packet size
        int declaredLength = data[2] & 0xFF;
        if (data.length != declaredLength + 5) { // 2 header + 1 length + 2 tail
            throw new ProtocolException(String.format(
                    "Packet length mismatch. Declared: %d, actual: %d",
                    declaredLength, data.length - 5));
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
            lastValidImei.set(imei);

            logger.info("Login request from IMEI: {}", imei);
            logger.info("IMEI bytes: {}", bytesToHex(imeiBytes));

            // Extract serial number (2 bytes before checksum)
            short serialNumber = buffer.getShort();
            logger.info("Serial number: {}", serialNumber);

            Variant variant = detectVariant(buffer.duplicate());
            byte vl03Extension = handleVl03Extension(buffer, variant, parsedData);

            // Generate appropriate response
            byte[] response = generateLoginResponse(variant, serialNumber, vl03Extension);
            parsedData.put("response", response);
            logger.info("Generated login response: {}", bytesToHex(response));

            // Handle session management
            DeviceSession session = manageDeviceSession(imei, serialNumber);

            // PROPERLY SET THE RESPONSE IN THE MESSAGE
            message.setResponseData(response);
            message.setResponseRequired(true);

            // Update message and notify handlers
            updateMessageAndNotify(message, imei, variant, response, session);

            return message;
        } catch (ProtocolException e) {
            throw e; // Re-throw protocol-specific exceptions
        } catch (Exception e) {
            logger.error("Login processing failed. Buffer: {}", bytesToHex(buffer.array()), e);
            throw new ProtocolException("Login processing failed: " + e.getMessage());
        }
    }

// Helper methods extracted from main handler:

    private byte handleVl03Extension(ByteBuffer buffer, Variant variant, Map<String, Object> parsedData) {
        byte vl03Extension = 0;
        if (variant == Variant.VL03 && buffer.remaining() >= 1) {
            vl03Extension = buffer.get();
            parsedData.put("vl03Extension", vl03Extension);
            logger.debug("VL03 extension byte: 0x{}", String.format("%02X", vl03Extension));
        }
        return vl03Extension;
    }

    private DeviceSession manageDeviceSession(String imei, short serialNumber) {
        DeviceSession existingSession = activeSessions.get(imei);

        if (existingSession != null) {
            if (!existingSession.isExpired()) {
                if (existingSession.isDuplicateSerialNumber(serialNumber)) {
                    logger.warn("Duplicate login from IMEI: {} (serial: {})", imei, serialNumber);
                } else {
                    existingSession.updateSerialNumber(serialNumber);
                    logger.debug("Updated session for IMEI {} with new serial {}", imei, serialNumber);
                }
                return existingSession;
            }
            logger.debug("Expired session found for IMEI {}, creating new one", imei);
        }

        DeviceSession newSession = new DeviceSession(imei, serialNumber);
        activeSessions.put(imei, newSession);
        logger.info("Created new session for IMEI {}", imei);
        return newSession;
    }

    private byte[] generateLoginResponse(Variant variant, short serialNumber, byte vl03Extension) {
        if (variant == Variant.VL03) {
            return generateVl03LoginResponse(serialNumber, vl03Extension);
        }
        return generateStandardLoginResponse(serialNumber);
    }

    private void updateMessageAndNotify(DeviceMessage message, String imei, Variant variant,
                                        byte[] response, DeviceSession session) throws Exception {
        session.updateLastActive();

        // Notify acknowledgement handler
        acknowledgementHandler.write(null, new AcknowledgementHandler.EventHandled(response), null);

        // Update message
        message.setImei(imei);
        message.setMessageType("LOGIN");
        message.getParsedData().put("variant", variant.name());
        message.getParsedData().put("sessionId", session.getSessionId());
    }

    private byte[] generateVl03LoginResponse(short serialNumber, byte extension) {
        // VL03 requires a 14-byte login response
        byte[] response = new byte[14];

        // Header
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;

        // Length (0x0B = 11 bytes after length)
        response[2] = 0x0B;

        // Protocol type (login response)
        response[3] = PROTOCOL_LOGIN;

        // Serial number
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber & 0xFF);

        // VL03-specific fields
        response[6] = 0x01;  // Login success
        response[7] = extension;  // Echo back the extension byte
        response[8] = 0x00;  // Reserved
        response[9] = 0x00;  // Reserved
        response[10] = 0x00; // Reserved

        // Calculate checksum (bytes 2-10)
        int checksum = Checksum.crc16(Checksum.CRC16_X25,
                ByteBuffer.wrap(response, 2, 9));

        response[11] = (byte)(checksum >> 8);
        response[12] = (byte)(checksum & 0xFF);
        response[13] = 0x0D;  // Footer
        response[14] = 0x0A;

        return response;
    }

    private byte[] generateStandardLoginResponse(short serialNumber) {
        byte[] response = new byte[10];
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x05; // Length
        response[3] = PROTOCOL_LOGIN;
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber);
        response[6] = 0x01; // Success status

        // Calculate checksum
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 5);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[7] = (byte)(checksum >> 8);
        response[8] = (byte)(checksum);
        response[9] = 0x0A; // Termination byte
        logger.info("Generated login response for serial {}: {}",
                serialNumber, bytesToHex(response));
        return response;
    }

    private String extractImei(byte[] imeiBytes) throws ProtocolException {
        StringBuilder imei = new StringBuilder();
        for (byte b : imeiBytes) {
            int high = (b >> 4) & 0x0F;
            int low = b & 0x0F;
            imei.append(high).append(low);
        }

        String imeiStr = imei.toString();
        // Remove leading zeros until we get 15 digits
        while (imeiStr.length() > 15 && imeiStr.startsWith("0")) {
            imeiStr = imeiStr.substring(1);
        }

        if (imeiStr.length() != 15 || !imeiStr.matches("^\\d{15}$")) {
            throw new ProtocolException("Invalid IMEI format. Expected 15 digits, got: " + imeiStr);
        }

        return imeiStr;
    }

    private DeviceMessage handleGps(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData)
            throws Exception {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        Position position = parseGpsData(buffer);
        parsedData.put("position", position);

        byte[] response = generateStandardResponse(PROTOCOL_GPS, (short)0, (byte)0x01);
        parsedData.put("response", response);

        // Notify acknowledgement handler of GPS data
        acknowledgementHandler.write(null, new AcknowledgementHandler.EventHandled(response), null);

        message.setImei(imei);
        message.setMessageType("GPS");
        return message;
    }

    private Position parseGpsData(ByteBuffer buffer) throws ProtocolException {
        Position position = new Position();
        Device device = new Device();
        device.setImei(lastValidImei.get());
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

    private DeviceMessage handleHeartbeat(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData)
            throws Exception {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        byte[] response = generateStandardResponse(PROTOCOL_HEARTBEAT, (short)0, (byte)0x01);
        parsedData.put("response", response);

        // Notify acknowledgement handler of heartbeat
        acknowledgementHandler.write(null, new AcknowledgementHandler.EventHandled(response), null);

        message.setImei(imei);
        message.setMessageType("HEARTBEAT");
        return message;
    }

    private DeviceMessage handleAlarm(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData)
            throws Exception {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        Position position = parseGpsData(buffer);
        position.setAlarmType(extractAlarmType(buffer));

        byte[] response = generateStandardResponse(PROTOCOL_ALARM, (short)0, (byte)0x01);
        parsedData.put("response", response);
        parsedData.put("position", position);

        // Notify acknowledgement handler of alarm
        acknowledgementHandler.write(null, new AcknowledgementHandler.EventHandled(response), null);

        message.setImei(imei);
        message.setMessageType("ALARM");
        return message;
    }

    /*private String extractAlarmType(ByteBuffer buffer) {
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
    }*/

    private byte[] generateStandardResponse(byte protocol, short serialNumber, byte status) {
        byte[] response = new byte[10];
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x05; // Length
        response[3] = protocol;
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber);
        response[6] = status;

        // Calculate checksum
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 5);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[7] = (byte)(checksum >> 8);
        response[8] = (byte)(checksum);
        response[9] = 0x0A;

        return response;
    }

    private byte[] generateErrorResponse(Exception error) {
        byte errorCode = getErrorCode(error);
        return generateStandardResponse(PROTOCOL_ERROR, (short)0, errorCode);
    }

    private byte getErrorCode(Exception error) {
        String message = error.getMessage();
        if (message.contains("IMEI")) return 0x01;
        if (message.contains("checksum")) return 0x02;
        if (message.contains("header")) return 0x03;
        if (message.contains("length")) return 0x04;
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
        return "GT06".equalsIgnoreCase(protocol);
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
        return generateStandardResponse(PROTOCOL_LOGIN, (short)0, (byte)0x01);
    }

    private DeviceMessage handleVl03Extended(ByteBuffer buffer,
                                             DeviceMessage message,
                                             Map<String, Object> parsedData) {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI for VL03 extended message");
        }

        // Parse VL03-specific extended data format
        int extensionType = buffer.get() & 0xFF;
        Position position = new Position();

        switch (extensionType) {
            case 0x01:
                // VL03 extended location report
                position = parseVl03GpsData(buffer);
                break;
            case 0x02:
                // VL03 sensor data
                position = parseVl03SensorData(buffer);
                break;
            default:
                throw new ProtocolException("Unknown VL03 extension type: " + extensionType);
        }

        byte[] response = generateVl03Response(extensionType);
        parsedData.put("response", response);
        parsedData.put("position", position);

        message.setImei(imei);
        message.setMessageType("VL03_EXTENDED");
        return message;
    }

    private Position parseVl03GpsData(ByteBuffer buffer) {
        Position position = parseGpsData(buffer); // Start with standard parsing

        // VL03-specific additions
        //position.set("vl03Hdop", buffer.get() & 0xFF); // Example extra field
        //position.set("vl03Voltage", buffer.getShort() / 1000.0);

        return position;
    }

    private byte[] generateVl03Response(int extensionType) {
        ByteBuffer buf = ByteBuffer.allocate(12)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte)0x07) // Length
                .put(VL03_PROTOCOL_EXTENDED)
                .put((byte)extensionType)
                .putShort((short)0x0000); // Placeholder for serial

        // Calculate checksum
        byte[] data = buf.array();
        int checksum = Checksum.crc16(Checksum.CRC16_X25,
                ByteBuffer.wrap(data, 2, 5));

        return ByteBuffer.allocate(12)
                .put(data, 0, 8)
                .putShort((short)checksum)
                .put((byte)0x0D)
                .put((byte)0x0A)
                .array();
    }

    // Modified alarm handler for VL03
    private DeviceMessage handleAlarm(ByteBuffer buffer, DeviceMessage message,
                                      Map<String, Object> parsedData, Variant variant) {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        Position position = parseGpsData(buffer);

        // VL03-specific alarm handling
        if (variant == Variant.VL03) {
            int alarmCode = buffer.get() & 0xFF;
            position.setAlarmType(translateVl03Alarm(alarmCode));

            // VL03 sends additional alarm info
            if (alarmCode == VL03_ALARM_TYPE) {
                //position.set("alarmDetail", buffer.getShort());
            }
        } else {
            position.setAlarmType(extractAlarmType(buffer));
        }

        byte[] response = variant == Variant.VL03 ?
                generateVl03AlarmResponse() :
                generateStandardResponse(PROTOCOL_ALARM, (short)0, (byte)0x01);

        parsedData.put("response", response);
        parsedData.put("position", position);

        message.setImei(imei);
        message.setMessageType("ALARM");
        return message;
    }

    private String translateVl03Alarm(int code) {
        // VL03-specific alarm codes
        switch (code) {
            case 0xA0: return "VL03_HARD_ACCELERATION";
            case 0xA1: return "VL03_HARD_BRAKING";
            case 0xA2: return "VL03_CRASH_DETECTION";
            case 0xA3: return "VL03_TOW_ALARM";
            case 0xA4: return "VL03_JAMMING_DETECTION";
            case 0xA5: return "VL03_FATIGUE_DRIVING";
            case 0xB0: return "VL03_ENGINE_ALARM";
            case 0xC0: return "VL03_MAINTENANCE_ALERT";
        }

        // Standard GT06 alarm codes
        switch (code) {
            case 0x01: return "SOS";
            case 0x02: return "POWER_CUT";
            case 0x03: return "VIBRATION";
            case 0x04: return "ENTER_GEOFENCE";
            case 0x05: return "EXIT_GEOFENCE";
            case 0x06: return "OVERSPEED";
            case 0x09: return "LOW_BATTERY";
            case 0x10: return "POWER_ON";
            default: return String.format("UNKNOWN_ALARM_%02X", code);
        }
    }

    private Position parseVl03SensorData(ByteBuffer buffer) throws ProtocolException {
        Position position = new Position();
        Device device = new Device();
        device.setImei(lastValidImei.get());
        device.setProtocolType("GT06-VL03");
        position.setDevice(device);

        // Parse standard timestamp (YY MM DD HH MM SS)
        position.setTimestamp(LocalDateTime.of(
                2000 + (buffer.get() & 0xFF), // year
                buffer.get() & 0xFF,           // month
                buffer.get() & 0xFF,           // day
                buffer.get() & 0xFF,           // hour
                buffer.get() & 0xFF,           // minute
                buffer.get() & 0xFF            // second
        ));

        // VL03-specific sensor data format:
        //position.set(Position.KEY_BATTERY, buffer.getShort() / 1000.0);  // Battery voltage in volts
        //position.set(Position.KEY_POWER, buffer.getShort() / 1000.0);    // External power voltage

        // Digital inputs (1 byte where each bit represents an input)
        byte digitalInputs = buffer.get();
        for (int i = 0; i < 8; i++) {
            //position.set(Position.PREFIX_IN + (i + 1), BitUtil.check(digitalInputs, i));
        }

        // Analog inputs (4 channels)
        for (int i = 0; i < 4; i++) {
            //position.set(Position.PREFIX_ADC + (i + 1), buffer.getShort() / 100.0);
        }

        // Temperature sensors (2 channels)
        //position.set(Position.PREFIX_TEMP + 1, buffer.getShort() / 10.0); // Sensor 1 in °C
        //position.set(Position.PREFIX_TEMP + 2, buffer.getShort() / 10.0); // Sensor 2 in °C

        // Additional VL03-specific fields
        //position.set("fuelLevel", buffer.get() & 0xFF);       // 0-100%
        //position.set("rpm", buffer.getShort() & 0xFFFF);      // Engine RPM
        //position.set("odo", buffer.getInt() & 0xFFFFFFFFL);   // Odometer in meters

        // Verify we have enough data for checksum
        if (buffer.remaining() < 4) {
            throw new ProtocolException("VL03 sensor data packet too short");
        }

        return position;
    }

    private byte[] generateVl03AlarmResponse() {
        // VL03 requires a 14-byte response for alarm acknowledgments
        byte[] response = new byte[14];

        // Header
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;

        // Length (0x0B = 11 bytes after length)
        response[2] = 0x0B;

        // Protocol type (alarm response)
        response[3] = PROTOCOL_ALARM;

        // VL03-specific fields
        response[4] = 0x01;  // Acknowledgment code
        response[5] = 0x00;  // Reserved
        response[6] = 0x00;  // Reserved

        // Timestamp (current server time)
        LocalDateTime now = LocalDateTime.now();
        response[7] = (byte)(now.getYear() - 2000);
        response[8] = (byte)now.getMonthValue();
        response[9] = (byte)now.getDayOfMonth();
        response[10] = (byte)now.getHour();
        response[11] = (byte)now.getMinute();
        response[12] = (byte)now.getSecond();

        // Calculate checksum (bytes 2-12)
        int checksum = Checksum.crc16(Checksum.CRC16_X25,
                ByteBuffer.wrap(response, 2, 11));

        response[13] = (byte)(checksum >> 8);
        response[14] = (byte)(checksum & 0xFF);
        response[15] = 0x0D;  // Footer
        response[16] = 0x0A;

        return response;
    }

    private String extractAlarmType(ByteBuffer buffer) {
        if (buffer.remaining() < 1) {
            return "UNKNOWN";
        }

        int alarmType = buffer.get() & 0xFF;

        // Common GT06 alarms
        switch (alarmType) {
            case 0x01: return "SOS";
            case 0x02: return "POWER_CUT";
            case 0x03: return "VIBRATION";
            case 0x04: return "ENTER_GEOFENCE";
            case 0x05: return "EXIT_GEOFENCE";
            case 0x06: return "OVERSPEED";
            case 0x09: return "LOW_BATTERY";
            case 0x10: return "POWER_ON";

            // VL03-specific alarms
            case 0xA0: return "VL03_HARD_ACCELERATION";
            case 0xA1: return "VL03_HARD_BRAKING";
            case 0xA2: return "VL03_CRASH_DETECTION";
            case 0xA3: return "VL03_TOW_ALARM";
            case 0xA4: return "VL03_JAMMING_DETECTION";
            case 0xA5: return "VL03_FATIGUE_DRIVING";

            // Extended alarms with data payload
            case 0xB0:
                if (buffer.remaining() >= 2) {
                    int subCode = buffer.getShort() & 0xFFFF;
                    return String.format("VL03_ENGINE_ALARM_%04X", subCode);
                }
                return "VL03_ENGINE_ALARM";

            case 0xC0:
                return "VL03_MAINTENANCE_ALERT";

            default:
                return String.format("UNKNOWN_ALARM_%02X", alarmType);
        }
    }
}