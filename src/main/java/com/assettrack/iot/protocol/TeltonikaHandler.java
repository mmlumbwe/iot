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

    @Value("${teltonika.validation.mode:STRICT}")
    private ValidationMode validationMode;

    @Override
    public Position parsePosition(byte[] rawMessage) throws ProtocolException {
        if (rawMessage == null || rawMessage.length < TeltonikaConstants.HEADER_SIZE) {
            throw new ProtocolException("Message too short or null");
        }

        ByteBuffer buffer = ByteBuffer.wrap(rawMessage).order(ByteOrder.BIG_ENDIAN);
        Position position = new Position();
        Device device = new Device();

        try {
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

            byte[] imeiBytes = new byte[IMEI_LENGTH];
            buffer.get(imeiBytes);
            String imei = cleanImei(new String(imeiBytes, StandardCharsets.US_ASCII));

            if (!isValidImei(imei)) {
                throw new ProtocolException("Invalid IMEI: " + imei);
            }

            device.setImei(imei);
            device.setProtocolType("TELTONIKA");
            position.setDevice(device);

            switch (codecId) {
                case CODEC_8:
                case CODEC_8_EXT:
                    parseCodec8Data(buffer, position);
                    break;
                case CODEC_16:
                    parseCodec16Data(buffer, position);
                    break;
                default:
                    throw new ProtocolException("Unhandled codec: " + codecId);
            }

            return position;
        } catch (Exception e) {
            throw new ProtocolException("Failed to parse position", e);
        }
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
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

    private DeviceMessage handleDataPacket(byte[] data, DeviceMessage message) throws ProtocolException {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // Skip preamble and length (already validated)
        buffer.position(8);

        int codecId = buffer.get() & 0xFF;
        String protocolVersion = TeltonikaConstants.CODECS.getOrDefault(codecId, "UNKNOWN");
        message.setProtocolVersion(protocolVersion);

        // More lenient record count handling
        if (buffer.remaining() > 0) {
            int records = buffer.get() & 0xFF;
            message.addParsedData("records", records);

            if (records > 0 && buffer.remaining() >= 8) {
                long timestamp = buffer.getLong();
                message.setTimestamp(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(timestamp),
                        ZoneId.systemDefault()));
            }
        }

        // Generate response
        ByteBuffer response = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        response.putInt(0);
        response.putInt(1); // Acknowledge 1 packet
        message.addParsedData("response", response.array());

        return message;
    }

    private void parseCodec8Data(ByteBuffer buffer, Position position) throws ProtocolException {
        // Timestamp
        long timestamp = buffer.getLong();
        if (timestamp <= 0) {
            throw new ProtocolException("Invalid timestamp");
        }
        position.setTimestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()));

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
    }

    private void parseCodec16Data(ByteBuffer buffer, Position position) throws ProtocolException {
        parseCodec8Data(buffer, position);
        if (buffer.remaining() > 0) {
            buffer.get();
        }
        skipIoElements(buffer, CODEC_16);
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
        // Check length (15 digits)
        if (imei.length() != 15 || !imei.matches("\\d+")) {
            return false;
        }

        // Luhn check (optional but recommended)
        int sum = 0;
        for (int i = 0; i < imei.length(); i++) {
            int digit = Character.getNumericValue(imei.charAt(i));
            if (i % 2 != 0) { // Double every other digit
                digit *= 2;
                if (digit > 9) digit = digit - 9;
            }
            sum += digit;
        }
        return sum % 10 == 0;
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

    private DeviceMessage handleImeiPacket(byte[] data, DeviceMessage message) throws ProtocolException {
        int length = ((data[0] & 0xFF) << 8 | (data[1] & 0xFF));
        String imei = new String(data, 2, length, StandardCharsets.US_ASCII);

        if (!isValidImei(cleanImei(imei))) {
            throw new ProtocolException("Invalid IMEI format");
        }

        message.setImei(imei);
        message.setMessageType("IMEI");
        message.addParsedData("response", new byte[]{0x01});
        return message;
    }

    private boolean isImeiPacket(byte[] data) {
        if (data == null || data.length < 4) return false;
        int length = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        return length >= TeltonikaConstants.IMEI_MIN_LENGTH &&
                length <= TeltonikaConstants.IMEI_MAX_LENGTH &&
                data.length >= length + 2;
    }

    private boolean isSupportedCodec(int codecId) {
        return codecId == CODEC_8 || codecId == CODEC_8_EXT || codecId == CODEC_16;
    }

    public enum ValidationMode {
        STRICT, LENIENT, RECOVER
    }
}