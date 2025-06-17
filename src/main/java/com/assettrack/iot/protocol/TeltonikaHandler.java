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
import java.util.Map;
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

    // This is the method required by the ProtocolHandler interface
    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        // This method will not have ChannelHandlerContext directly.
        // We will call the existing method, passing null for ctx.
        // The existing method must handle null ctx gracefully.
        return handle(data, null); // Delegate to the method with ChannelHandlerContext
    }


    // Removed @Override because this specific signature is likely not from the interface
    public DeviceMessage handle(byte[] data, ChannelHandlerContext ctx) throws ProtocolException {
        logger.info(
                "→ TeltonikaHandler.handle(...) called; data.length={}, ctx={}",
                data.length,
                ctx
        );
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("TELTONIKA");

        try {
            if (isImeiPacket(data)) {
                message = handleImeiPacket(data, message);
                if (ctx != null) {
                    // IMEI ACK should be a single byte 0x01
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x01}));
                    logger.info("Sent login request (0x01) to device: {}", message.getImei());
                }
                return message;
            } else if (isDataPacket(data)) {
                message = handleDataPacket(data, message);
                if (ctx != null) {
                    // Corrected line: First get the map, then extract the value by key
                    Map<String, Object> parsedData = message.getParsedData(); // Call getParsedData() with no arguments
                    byte[] responseBytes = (byte[]) parsedData.get("response"); // Get "response" from the map

                    if (responseBytes != null && responseBytes.length > 0) {
                        ctx.writeAndFlush(Unpooled.wrappedBuffer(responseBytes));
                        logger.info("Sent data acknowledgment ({} bytes) to device.", responseBytes.length);
                    } else {
                        logger.warn("No acknowledgment response generated for data packet. Not sending ACK.");
                    }
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


    private DeviceMessage handleDataPacket(byte[] data, DeviceMessage message) throws ProtocolException {
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
            int recordCount) throws ProtocolException {

        logger.info("→ Entered processCodec8Packet; buffer.position={}, remainingBytes={}",
                buffer.position(), buffer.remaining());

        // Loop through each record
        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < recordCount; i++) {
            // Store the starting position of the current record for debugging/validation
            int recordStartPosition = buffer.position();
            logger.info("→ Parsing record #{}/{} starting at buffer position {}", i + 1, recordCount, recordStartPosition);

            // Minimum bytes for one record (fixed part + 1 byte Event ID)
            // Fixed part: Timestamp (8) + Priority (1) + Lon (4) + Lat (4) + Altitude (2) + Course (2) + Sats (1) + Speed (2) = 24 bytes
            // Event ID (1 byte) = 1 byte
            // Total = 25 bytes
            if (buffer.remaining() < 25) { // Updated to 25 bytes
                logger.warn("→ Not enough bytes for fixed part + Event ID of record #{} (remaining={})", i + 1, buffer.remaining());
                break; // Exit loop if not enough bytes for even the fixed part and Event ID
            }
            try {
                Position pos = parseCodec8Data(buffer); // parseCodec8Data now consumes fixed part + Event ID
                logger.info("→ Parsed fixed part of record #{}: ts={}, lat={}, lon={}",
                        i + 1, pos.getTimestamp(), pos.getLatitude(), pos.getLongitude());

                // Now skip the I/O elements for *this* record (starting from I/O counts)
                skipIoElements(buffer, CODEC_8);
                logger.info("→ Skipped I/O elements for record #{}; new buffer position={}", i + 1, buffer.position());


                // associate device
                if (message.getImei() != null) {
                    Device d = new Device();
                    d.setImei(message.getImei());
                    d.setProtocolType("TELTONIKA");
                    pos.setDevice(d);
                }

                positions.add(pos);
                // Removed: if (!positions.isEmpty()) { break; } // This line was causing early exit
            } catch (ProtocolException ex) {
                logger.warn("→ Failed to parse record #{}", i + 1, ex);
                // In case of a parsing failure for a record, attempt to advance the buffer
                // by the expected size of a fixed record + event ID (25 bytes) if possible,
                // to try and parse subsequent records. This assumes the error is within
                // the I/O elements part or that the fixed part was read partially.
                // A more robust error recovery might try to determine the end of the current
                // malformed record. For now, we skip forward 25 bytes if we failed due to
                // coordinate/timestamp, assuming the buffer is still roughly at the start
                // of the fixed part + event ID for the next record.
                if (buffer.remaining() >= 25) { // Try to advance past the expected fixed part + Event ID
                    buffer.position(recordStartPosition + 25); // Move to the start of where the IO counts *should* have been
                    // And then try to skip the (potentially malformed) IO elements
                    try {
                        skipIoElements(buffer, CODEC_8);
                        logger.info("→ Attempted to recover buffer position for record #{} after parsing failure. New position: {}", i + 1, buffer.position());
                    } catch (Exception ioEx) {
                        logger.warn("→ Further error during I/O element skipping during recovery for record #{}: {}", i + 1, ioEx.getMessage());
                        // If I/O skipping also fails, we can't reliably determine the next record's start.
                        // Break or skip to the end of the buffer to avoid further errors.
                        buffer.position(buffer.limit()); // Mark buffer as fully consumed
                    }
                } else {
                    buffer.position(buffer.limit()); // Not enough bytes to even recover
                }
            }
        }

        message.addParsedData("positions", positions);
        if (!positions.isEmpty()) {
            // Set timestamp to the timestamp of the last processed position
            message.setTimestamp(positions.get(positions.size() - 1).getTimestamp());
        }

        // ACK: echo back recordCount of *processed* records as a 4-byte integer
        ByteBuffer ack = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN); // Allocate only 4 bytes
        ack.putInt(positions.size()); // Acknowledge only the records that were successfully processed
        message.addParsedData("response", ack.array());
        logger.info("→ processCodec8Packet: generated ACK for {} records", positions.size());

        return message;
    }

    private Position parseCodec8Data(ByteBuffer buffer) throws ProtocolException {
        Position position = new Position();

        // 1) Timestamp (8 bytes)
        long ts = buffer.getLong();
        position.setTimestamp(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())
        );

        // 2) Priority (1 byte) — drop
        int priority = buffer.get() & 0xFF;
        logger.debug("→ parseCodec8Data: priority={}", priority);

        // 3) Coordinates: LONG first, then LAT (each 4 bytes, scaled 1e7)
        int lonRaw = buffer.getInt();
        int latRaw = buffer.getInt();
        double longitude = lonRaw / 1e7;
        double latitude  = latRaw / 1e7;
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

        // --- NEW ADDITION: Skip Event ID (1 byte) ---
        if (buffer.remaining() > 0) {
            int eventId = buffer.get() & 0xFF;
            logger.debug("→ parseCodec8Data: skipped Event ID={}", eventId);
        } else {
            // This indicates a malformed packet if we expect an Event ID but it's not there.
            throw new ProtocolException("Missing Event ID after fixed AVL data.");
        }
        // --- END NEW ADDITION ---

        return position;
    }


    private DeviceMessage processCodec16Packet(ByteBuffer buffer, DeviceMessage message, int recordCount) throws ProtocolException { // Added throws ProtocolException
        logger.info("→ Entered processCodec16Packet; buffer.position={}, remainingBytes={}",
                buffer.position(), buffer.remaining());

        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < recordCount; i++) {
            int recordStartPosition = buffer.position();
            logger.info("→ Parsing Codec16 record #{}/{} starting at buffer position {}", i + 1, recordCount, recordStartPosition);

            // Minimum bytes for one record (fixed part + 1 byte Event ID) - same as Codec8 for base fields + event ID
            if (buffer.remaining() < 25) { // Assuming 25 bytes fixed part + Event ID for Codec16
                logger.warn("→ Not enough bytes for fixed part + Event ID of Codec16 record #{} (remaining={})", i + 1, buffer.remaining());
                break;
            }

            try {
                // Use the same parsing as Codec8 for base fields + Event ID
                Position position = parseCodec8Data(buffer); // Now parses fixed part + Event ID
                logger.info("→ Parsed fixed part of Codec16 record #{}: ts={}, lat={}, lon={}",
                        i + 1, position.getTimestamp(), position.getLatitude(), position.getLongitude());

                // Handle Codec16 specific fields - this primarily means skipping I/O elements
                // The `skipIoElements` method will now correctly start from the I/O counts.
                skipIoElements(buffer, CODEC_16);
                logger.info("→ Skipped I/O elements for Codec16 record #{}; new buffer position={}", i + 1, buffer.position());

                if (message.getImei() != null) {
                    Device d = new Device();
                    d.setImei(message.getImei());
                    d.setProtocolType("TELTONIKA");
                    position.setDevice(d);
                }
                positions.add(position);
                // Removed: if (!positions.isEmpty()) { break; } // This line was causing early exit
            } catch (ProtocolException e) {
                logger.warn("Failed to parse Codec16 record #{}", i + 1, e);
                // Recovery mechanism similar to processCodec8Packet
                if (buffer.remaining() >= 25) {
                    buffer.position(recordStartPosition + 25);
                    try {
                        skipIoElements(buffer, CODEC_16);
                        logger.info("→ Attempted to recover buffer position for Codec16 record #{} after parsing failure. New position: {}", i + 1, buffer.position());
                    } catch (Exception ioEx) {
                        logger.warn("→ Further error during I/O element skipping during recovery for Codec16 record #{}: {}", i + 1, ioEx.getMessage());
                        buffer.position(buffer.limit());
                    }
                } else {
                    buffer.position(buffer.limit());
                }
            }
        }
        message.addParsedData("positions", positions); // Store all positions

        if (!positions.isEmpty()) {
            // Set timestamp to the timestamp of the last processed position
            message.setTimestamp(positions.get(positions.size() - 1).getTimestamp());
        }

        // Generate response (4-byte integer)
        ByteBuffer response = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        response.putInt(positions.size()); // Acknowledge only the records that were successfully processed
        message.addParsedData("response", response.array());

        return message;
    }

    private Position parseCodec16Data(ByteBuffer buffer) throws ProtocolException {
        // This method is called by parsePosition directly when only one position is expected.
        // It needs to handle skipping I/O elements itself in this context.
        Position position = parseCodec8Data(buffer); // Parse fixed part + Event ID

        if (buffer.remaining() > 0) {
            skipIoElements(buffer, CODEC_16); // Skip I/O elements for this single record
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
        // The original line below is commented out to ignore invalid coordinates
        // if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
        //     throw new ProtocolException("Invalid coordinates: lat=" + latitude + ", lon=" + longitude);
        // }
    }

    // Refactored skipIoElements to directly implement the logic
    // from the most recent correction, instead of delegating to skipIoElementsOfSize
    private void skipIoElements(ByteBuffer buffer, int codecId) throws ProtocolException {
        int beforeAll = buffer.position();
        int[] sizes = {1, 2, 4, 8};
        for (int size : sizes) {
            // only 8‐byte for codec 8
            if (size == 8 && codecId != CODEC_8) continue;

            // grab the count
            if (buffer.remaining() < 1) {
                throw new ProtocolException("Missing count for " + size + "-byte group");
            }
            int count = buffer.get() & 0xFF;
            int beforeGroup = buffer.position();

            logger.info("→ I/O group {}-byte: count = {}", size, count);

            // skip each element: 1‐byte ID + `size`‐byte payload
            for (int i = 0; i < count; i++) {
                if (buffer.remaining() < 1 + size) {
                    throw new ProtocolException(
                            "Not enough bytes for " + size + "-byte element " +
                                    "(" + (i+1) + "/" + count + "), remaining=" + buffer.remaining()
                    );
                }
                buffer.get();                                    // ID
                buffer.position(buffer.position() + size);       // payload
            }

            int afterGroup = buffer.position();
            int consumed = afterGroup - beforeGroup;
            int expected = count * (1 + size);
            logger.info(
                    "   → Group {}-byte: expected to skip {} bytes, actually skipped {} bytes",
                    size, expected, consumed
            );
        }
        int afterAll = buffer.position();
        logger.info("→ skipIoElements: total consumed = {} bytes", afterAll - beforeAll);
    }


    // New helper method for skipping I/O elements of a specific size,
    // handling insufficient bytes by advancing to the end of the buffer.
    private void skipIoElementsOfSpecificSize(ByteBuffer buffer, int sizeBytes) throws ProtocolException {
        if (buffer.remaining() > 0) {
            int count = buffer.get() & 0xFF; // Reads one byte for count
            logger.debug("→ skipIoElementsOfSpecificSize ({} bytes): count={}", sizeBytes, count);
            int expectedBytesForIoElements = count * (1 + sizeBytes);

            if (buffer.remaining() >= expectedBytesForIoElements) {
                // Enough bytes available, advance position normally
                buffer.position(buffer.position() + expectedBytesForIoElements);
            } else {
                // Not enough bytes, log a warning and throw an exception for malformed data.
                // It's critical to throw here because if we just advance to the limit,
                // the next record's start will be completely unpredictable.
                logger.warn("Not enough bytes to skip {}-byte I/O elements. Remaining in buffer: {}, Expected to skip: {} (Count: {})",
                        sizeBytes, buffer.remaining(), expectedBytesForIoElements, count);
                throw new ProtocolException("Malformed I/O data: not enough bytes for " + sizeBytes + "-byte I/O elements (Count: " + count + ").");
            }
        } else {
            // This is a ProtocolException because the count byte itself is missing.
            throw new ProtocolException("Malformed I/O data: missing " + sizeBytes + "-byte I/O count.");
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
        // This method is likely for single-position responses, not the data packet ACK.
        // It should also return a 4-byte count if used for data acknowledgments.
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(1); // Acknowledge 1 record
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