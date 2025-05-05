package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.regex.Pattern;

@Component
@Protocol(value = "TELTONIKA", version = "CODEC8")
public class TeltonikaHandler implements ProtocolHandler {
    private static final Logger logger = LoggerFactory.getLogger(TeltonikaHandler.class);
    private static final int CODEC_8 = 0x08;
    private static final int CODEC_8_EXT = 0x8E;
    private static final int CODEC_16 = 0x10;
    private static final int IMEI_LENGTH = 15;
    private static final Pattern IMEI_PATTERN = Pattern.compile("^\\d{15}$");
    private static final byte[] HEARTBEAT_RESPONSE = new byte[] {0x00, 0x00, 0x00, 0x01};

    @Value("${teltonika.validation.mode:STRICT}")
    private ValidationMode validationMode;

    @Override
    public Position parsePosition(byte[] rawMessage) throws ProtocolException {
        if (rawMessage == null || rawMessage.length < TeltonikaConstants.HEADER_SIZE) {
            throw new ProtocolException("Message too short or null");
        }

        ByteBuffer buffer = ByteBuffer.wrap(rawMessage).order(ByteOrder.BIG_ENDIAN);

        try {
            // Validate packet structure
            if (buffer.getInt() != 0) {
                throw new ProtocolException("Invalid preamble");
            }

            int dataLength = buffer.getInt();
            if (rawMessage.length < dataLength + TeltonikaConstants.HEADER_SIZE) {
                throw new ProtocolException("Invalid data length");
            }

            int codecId = buffer.get() & 0xFF;
            if (!isSupportedCodec(codecId)) {
                throw new ProtocolException("Unsupported codec: " + codecId);
            }

            // Parse IMEI
            byte[] imeiBytes = new byte[IMEI_LENGTH];
            buffer.get(imeiBytes);
            String imei = cleanImei(new String(imeiBytes, StandardCharsets.US_ASCII));

            if (!isValidImei(imei)) {
                throw new ProtocolException("Invalid IMEI: " + imei);
            }

            // Create device
            Device device = new Device();
            device.setImei(imei);
            device.setProtocolType("TELTONIKA");

            // Parse position data based on codec
            Position position;
            switch (codecId) {
                case CODEC_8:
                case CODEC_8_EXT:
                    position = parseCodec8Data(buffer);
                    break;
                case CODEC_16:
                    position = parseCodec16Data(buffer);
                    break;
                default:
                    throw new ProtocolException("Unhandled codec: " + codecId);
            }

            position.setDevice(device);
            return position;

        } catch (Exception e) {
            throw new ProtocolException("Failed to parse position", e);
        }
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        if (data == null || data.length == 0) {
            throw new ProtocolException("Empty data received");
        }

        // Check for heartbeat first
        if (isHeartbeatPacket(data)) {
            return handleHeartbeat();
        }

        DeviceMessage message = new DeviceMessage();
        message.setProtocol("TELTONIKA");

        try {
            if (isImeiPacket(data)) {
                return handleImeiPacket(data, message);
            } else {
                return handleDataPacket(data, message);
            }
        } catch (Exception e) {
            throw new ProtocolException("Failed to handle Teltonika message", e);
        }
    }

    private DeviceMessage handleImeiPacket(byte[] data, DeviceMessage message) throws ProtocolException {
        // Validate packet length
        if (data.length < 4 || data.length > 19) {
            throw new ProtocolException("Invalid IMEI packet length");
        }

        int length = ((data[0] & 0xFF) << 8 | (data[1] & 0xFF));
        if (length < 15 || length > 17) {
            throw new ProtocolException("Invalid IMEI length");
        }

        String imei = new String(data, 2, length, StandardCharsets.US_ASCII);
        if (!isValidImei(cleanImei(imei))) {
            throw new ProtocolException("Invalid IMEI format");
        }

        message.setImei(imei);
        message.setMessageType("IMEI");

        // Some devices expect 8-byte response (like data packets)
        ByteBuffer response = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        response.putInt(0x00000000);  // Preamble
        response.putInt(1);       // Number of accepted packets
        response.putInt(123456);  // Example session ID - should be dynamic in real implementation
        message.addParsedData("response", response.array());

        logger.info("Accepted IMEI from device {}", imei);
        return message;
    }

    private DeviceMessage handleDataPacket(byte[] data, DeviceMessage message) throws ProtocolException {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Validate packet structure
            if (buffer.remaining() < 12) {
                throw new ProtocolException("Packet too short");
            }

            // Parse header
            if (buffer.getInt() != 0) {
                throw new ProtocolException("Invalid preamble");
            }

            int dataLength = buffer.getInt();
            if (data.length < dataLength + 8) {
                throw new ProtocolException("Packet length mismatch");
            }

            int codecId = buffer.get() & 0xFF;
            String protocolVersion = TeltonikaConstants.CODECS.getOrDefault(codecId, "UNKNOWN");
            message.setProtocolVersion(protocolVersion);
            message.setMessageType("DATA"); // Ensure message type is set

            // Process based on codec type
            switch (codecId) {
                case CODEC_8:
                case CODEC_8_EXT:
                    return processCodec8Packet(buffer, message);
                case CODEC_16:
                    return processCodec16Packet(buffer, message);
                default:
                    throw new ProtocolException("Unsupported codec: " + codecId);
            }
        } catch (Exception e) {
            message.setMessageType("ERROR");
            message.addParsedData("error", e.getMessage());
            throw new ProtocolException("Failed to handle data packet", e);
        }
    }

    private DeviceMessage processCodec8Packet(ByteBuffer buffer, DeviceMessage message) {
        message.setMessageType("DATA");

        int records = buffer.get() & 0xFF;
        message.addParsedData("records", records);

        if (records > 0 && buffer.remaining() >= 8) {
            try {
                Position position = parseCodec8Data(buffer);

                // Ensure device is set from session
                if (message.getImei() != null) {
                    Device device = new Device();
                    device.setImei(message.getImei());
                    device.setProtocolType("TELTONIKA");
                    position.setDevice(device);
                }

                message.addParsedData("position", position);
                message.setTimestamp(position.getTimestamp());
            } catch (ProtocolException e) {
                logger.warn("Failed to parse position data", e);
            }
        }

        // Generate response
        ByteBuffer response = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        response.putInt(0); // Preamble
        response.putInt(records); // Number of accepted records
        message.addParsedData("response", response.array());

        return message;
    }

    private Position parseCodec8Data(ByteBuffer buffer) throws ProtocolException {
        Position position = new Position();

        // Timestamp
        long timestamp = buffer.getLong();
        if (timestamp <= 0) {
            throw new ProtocolException("Invalid timestamp");
        }
        position.setTimestamp(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp),
                ZoneId.systemDefault()));

        // Coordinates
        double latitude = buffer.getInt() / 10000000.0;
        double longitude = buffer.getInt() / 10000000.0;
        validateCoordinates(latitude, longitude);
        position.setLatitude(latitude);
        position.setLongitude(longitude);

        // Course and speed
        position.setCourse((double) (buffer.getShort() & 0xFFFF));
        int satellites = buffer.get() & 0xFF;
        position.setValid(satellites > 0);

        // Convert knots to km/h
        double speedKnots = buffer.getShort() & 0xFFFF;
        position.setSpeed(speedKnots * 1.852);

        // Skip remaining fields
        skipIoElements(buffer, CODEC_8);

        return position;
    }

    private DeviceMessage processCodec16Packet(ByteBuffer buffer, DeviceMessage message) {
        int records = buffer.get() & 0xFF;
        message.addParsedData("records", records);

        if (records > 0 && buffer.remaining() >= 8) {
            try {
                Position position = parseCodec16Data(buffer); // Now takes only buffer
                message.addParsedData("position", position);
                message.setTimestamp(position.getTimestamp());
            } catch (ProtocolException e) {
                logger.warn("Failed to parse position data", e);
            }
        }

        // Generate response
        ByteBuffer response = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        response.putInt(0);
        response.putInt(records);
        message.addParsedData("response", response.array());

        return message;
    }

    private Position parseCodec16Data(ByteBuffer buffer) throws ProtocolException {
        // Use the same parsing as Codec8 for base fields
        Position position = parseCodec8Data(buffer);

        // Handle Codec16 specific fields
        if (buffer.remaining() > 0) {
            buffer.get(); // Skip additional byte if present
            skipIoElements(buffer, CODEC_16);
        }

        return position;
    }

    private DeviceMessage handleHeartbeat() {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("TELTONIKA");
        message.setMessageType("HEARTBEAT");
        message.addParsedData("response", HEARTBEAT_RESPONSE);
        logger.debug("Responded to heartbeat");
        return message;
    }

    private boolean isHeartbeatPacket(byte[] data) {
        if (data == null) return false;

        // Standard 4-byte null heartbeat
        if (data.length == 4) {
            return data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 0;
        }

        // Alternative 8-byte heartbeat format
        if (data.length == 8) {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            return buffer.getInt() == 0 && buffer.getInt() == 0;
        }

        return false;
    }

    private boolean isImeiPacket(byte[] data) {
        if (data == null || data.length < 4) return false;

        int length = ((data[0] & 0xFF) << 8 | (data[1] & 0xFF));
        return length >= 15 && length <= 17 && data.length >= length + 2;
    }

    private void validateCoordinates(double latitude, double longitude) throws ProtocolException {
        if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw new ProtocolException("Invalid coordinates: lat=" + latitude + ", lon=" + longitude);
        }
    }

    private void skipIoElements(ByteBuffer buffer, int codecId) {
        skipIoElementsOfSize(buffer, 1);
        skipIoElementsOfSize(buffer, 2);
        skipIoElementsOfSize(buffer, 4);
        if (codecId == CODEC_8 || codecId == CODEC_8_EXT || codecId == CODEC_16) {
            skipIoElementsOfSize(buffer, 8);
        }
    }

    private void skipIoElementsOfSize(ByteBuffer buffer, int sizeBytes) {
        if (buffer.remaining() > 0) {
            int count = buffer.get() & 0xFF;
            int bytesToSkip = count * (1 + sizeBytes);
            if (buffer.remaining() >= bytesToSkip) {
                buffer.position(buffer.position() + bytesToSkip);
            }
        }
    }

    private String cleanImei(String rawImei) {
        return rawImei != null ? rawImei.replaceAll("[^0-9]", "") : "";
    }

    private boolean isValidImei(String imei) {
        if (imei == null || imei.length() != 15 || !IMEI_PATTERN.matcher(imei).matches()) {
            return false;
        }

        // Luhn check
        int sum = 0;
        for (int i = 0; i < imei.length(); i++) {
            int digit = Character.getNumericValue(imei.charAt(i));
            if (i % 2 != 0) { // Double every other digit (0-indexed)
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }

    private boolean isSupportedCodec(int codecId) {
        return codecId == CODEC_8 || codecId == CODEC_8_EXT || codecId == CODEC_16;
    }

    @Override
    public byte[] generateResponse(Position position) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(0); // Preamble
        buffer.putInt(1); // Number of accepted data packets
        return buffer.array();
    }

    @Override
    public boolean supports(String protocolType) {
        return "TELTONIKA".equalsIgnoreCase(protocolType);
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        return "TELTONIKA".equalsIgnoreCase(protocol) &&
                (version == null || version.startsWith("CODEC8") || version.startsWith("CODEC16"));
    }

    public enum ValidationMode {
        STRICT, LENIENT, RECOVER
    }
}