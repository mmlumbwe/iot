package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
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
import java.util.ArrayList;
import java.util.List;
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


    // In TeltonikaHandler.java
    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        // Implementation for when no ChannelHandlerContext is available
        return handle(data, null);
    }

    @Override
    public DeviceMessage handle(byte[] data, ChannelHandlerContext ctx) throws ProtocolException {
        logger.info(
                "→ TeltonikaHandler.handle(...) called; data.length={}, ctx={}",
                data.length,
                ctx
        );
        // Your existing implementation that uses the ChannelHandlerContext
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("TELTONIKA");

        try {
            if (isImeiPacket(data)) {
                message = handleImeiPacket(data, message);
                if (ctx != null) {
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x01}));
                    logger.info("Sent login request (0x01) to device: {}", message.getImei());
                }
                return message;
            } else if (isDataPacket(data)) {
                message = handleDataPacket(data, message);
                if (ctx != null) {
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x00}));
                }
                return message;
            }
            throw new ProtocolException("Unsupported Teltonika packet");
        } catch (Exception e) {
            logger.error("Error handling Teltonika packet: {}", e.getMessage());
            throw new ProtocolException("Processing failed", e);
        }
    }

    private boolean isDataPacket(byte[] data) {
        if (data == null || data.length < 12) {  // Minimum Teltonika data packet size
            return false;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Check preamble (4 zero bytes)
            if (buffer.getInt() != 0) {
                return false;
            }

            // Check data length (should match remaining packet size)
            int dataLength = buffer.getInt();
            if (dataLength <= 0 || dataLength > 1024 * 1024) {  // Reasonable max size
                return false;
            }

            // Check codec ID (should be one of supported codecs)
            int codecId = buffer.get() & 0xFF;
            if (!isSupportedCodec(codecId)) {
                return false;
            }

            // Basic structure validation passed
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    public DeviceMessage handleImeiPacket(byte[] data, DeviceMessage message) throws ProtocolException {
        // Validate packet structure (2 bytes length + IMEI)
        if (data == null || data.length < 17 || data.length > 19) {
            throw new ProtocolException("Invalid IMEI packet length");
        }

        int length = ((data[0] & 0xFF) << 8 | (data[1] & 0xFF));
        if (length != 15) {  // Teltonika requires exactly 15 digits
            throw new ProtocolException("IMEI must be 15 digits");
        }

        String imei = new String(data, 2, length, StandardCharsets.US_ASCII);
        imei = cleanImei(imei);

        if (!isValidImei(imei)) {
            throw new ProtocolException("Invalid IMEI: " + imei);
        }

        message.setImei(imei);
        message.setMessageType("IMEI");
        logger.info("Accepted IMEI: {}", imei);

        return message;
    }


    public DeviceMessage handleDataPacket(byte[] data, DeviceMessage message) throws ProtocolException {
        try {
            // Entry
            logger.info("→ Entered handleDataPacket; totalBytes={}, wrapping buffer", data.length);
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            logger.info("→ Buffer wrapped; remainingBytes={}", buffer.remaining());

            // 1) Skip Teltonika “preamble” (always zero)
            int preamble = buffer.getInt();
            logger.info("→ Skipped preamble; value=0x{} ({})",
                    Integer.toHexString(preamble), preamble);

            // 2) Read dataLength (actual packet length)
            int packetLength = buffer.getInt();
            logger.info("→ Read packetLength field={}; will process next {} bytes",
                    packetLength, buffer.remaining());
            if (data.length < packetLength + TeltonikaConstants.HEADER_SIZE) {
                logger.error("→ Packet too short: totalBytes={} < packetLength+HEADER={}",
                        data.length, packetLength + TeltonikaConstants.HEADER_SIZE);
                throw new ProtocolException("Invalid data length");
            }

            // 3) Read codec and count
            int codecId    = buffer.get() & 0xFF;
            int recordCount = buffer.get() & 0xFF;
            logger.info("→ codecId={}, recordCount={}", codecId, recordCount);

            // 4) Prepare message
            String version = TeltonikaConstants.CODECS.getOrDefault(codecId, "UNKNOWN");
            message.setProtocolVersion(version);
            message.setMessageType("DATA");

            // 5) Dispatch
            switch (codecId) {
                case CODEC_8:
                case CODEC_8_EXT:
                    return processCodec8Packet(buffer, message, recordCount);
                case CODEC_16:
                    return processCodec16Packet(buffer, message, recordCount);
                default:
                    logger.error("→ Unsupported codec: {}", codecId);
                    throw new ProtocolException("Unsupported codec: " + codecId);
            }

        } catch (Exception e) {
            logger.error("→ Error handling Teltonika data packet", e);
            message.setMessageType("ERROR");
            message.addParsedData("error", e.getMessage());
            throw new ProtocolException("Failed to handle data packet", e);
        }
    }


    private DeviceMessage processCodec8Packet(
            ByteBuffer buffer,
            DeviceMessage message,
            int recordCount) {

        logger.info("→ Entered processCodec8Packet; buffer.position={}, remainingBytes={}",
                buffer.position(), buffer.remaining());

        // Loop through each record
        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < recordCount; i++) {
            if (buffer.remaining() < 23) {  // min bytes for one record
                logger.warn("→ Not enough bytes for record #{} (remaining={})", i + 1, buffer.remaining());
                break;
            }
            logger.info("→ Parsing record #{}/{}", i + 1, recordCount);
            try {
                Position pos = parseCodec8Data(buffer);
                logger.info("→ Parsed record #{}: ts={}, lat={}, lon={}",
                        i + 1, pos.getTimestamp(), pos.getLatitude(), pos.getLongitude());

                // associate device
                if (message.getImei() != null) {
                    Device d = new Device();
                    d.setImei(message.getImei());
                    d.setProtocolType("TELTONIKA");
                    pos.setDevice(d);
                }

                positions.add(pos);
            } catch (ProtocolException ex) {
                logger.warn("→ Failed to parse record #{}", i + 1, ex);
            }
        }

        message.addParsedData("positions", positions);
        if (!positions.isEmpty()) {
            message.setTimestamp(positions.get(positions.size() - 1).getTimestamp());
        }

        // ACK: echo back recordCount
        ByteBuffer ack = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        ack.putInt(0);
        ack.putInt(recordCount);
        message.addParsedData("response", ack.array());
        logger.info("→ processCodec8Packet: generated ACK for {} records", recordCount);

        return message;
    }

    private Position parseCodec8Data(ByteBuffer buffer) throws ProtocolException {
        Position position = new Position();

        // 1) Timestamp (8 bytes)
        long ts = buffer.getLong();
        if (ts <= 0) {
            throw new ProtocolException("Invalid timestamp");
        }
        position.setTimestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()));

        // 2) Priority (1 byte) — drop
        int priority = buffer.get() & 0xFF;
        logger.debug("→ parseCodec8Data: priority={}", priority);

        // 3) Coordinates: LONG first, then LAT (each 4 bytes, scaled 1e7)
        int lonRaw = buffer.getInt();
        int latRaw = buffer.getInt();
        double longitude = lonRaw / 1e7;
        double latitude  = latRaw / 1e7;
        validateCoordinates(latitude, longitude);
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        logger.info("→ parseCodec8Data: lat={}, lon={}", latitude, longitude);

        // 4) Altitude (2 bytes)
        position.setAltitude(buffer.getShort());

        // 5) Course (2 bytes)
        position.setCourse((double)(buffer.getShort() & 0xFFFF));

        // 6) Satellites & validity
        int sats = buffer.get() & 0xFF;
        position.setValid(sats > 0);

        // 7) Speed (2 bytes, knots → km/h)
        double speedKnots = buffer.getShort() & 0xFFFF;
        position.setSpeed(speedKnots * 1.852);

        // 8) I/O elements (variable length)
        skipIoElements(buffer, CODEC_8);

        return position;
    }


    private DeviceMessage processCodec16Packet(ByteBuffer buffer, DeviceMessage message, int recordCount) {
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
        return data != null && data.length >= 2 &&
                ((data[0] & 0xFF) << 8 | (data[1] & 0xFF)) == data.length - 2;
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

    /*public void setValidationMode(ValidationMode validationMode) {
    }*/


    public enum ValidationMode {
        STRICT, LENIENT, RECOVER
    }
}