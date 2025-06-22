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
import java.util.Arrays;
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

    /** Convert a byte array to a hex string (uppercase, no separators). */
    private static String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
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




        logger.info("TeltonikaHandler: ENTER handle, data.length={}, firstBytes={}", data.length,
                data.length>4 ? String.format("%02X%02X%02X%02X", data[0],data[1],data[2],data[3]) : toHexString(data));
        logger.info("TeltonikaHandler: ENTER handle, data.length={}, first4={}",
                data.length,
                data.length >= 4 ? toHexString(Arrays.copyOf(data, 4)) : toHexString(data));


        if (isHeartbeatPacket(data)) {
            logger.debug("TeltonikaHandler: heartbeat packet received");
            if (ctx != null) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(HEARTBEAT_RESPONSE))
                        .addListener(f -> {
                            if (f.isSuccess()) {
                                logger.debug("TeltonikaHandler: heartbeat ACK sent");
                            } else {
                                logger.error("TeltonikaHandler: heartbeat ACK failed", f.cause());
                            }
                        });
            }
            return handleHeartbeat();
        }
        if (isImeiPacket(data)) {
            logger.info("TeltonikaHandler: IMEI packet – will ACK and create session");
            DeviceMessage msg = handleImeiPacket(data, message);
            if (ctx != null) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x01}))
                        .addListener(f -> {
                            if (f.isSuccess()) {
                                logger.info("TeltonikaHandler: login ACK (0x01) sent to {}", msg.getImei());
                            } else {
                                logger.error("TeltonikaHandler: failed to send login ACK", f.cause());
                            }
                        });
            }
            return msg;
        }
        if (isDataPacket(data)) {
            logger.info("TeltonikaHandler: DATA packet – about to parse {} bytes", data.length);
            DeviceMessage msg = handleDataPacket(data, message);
            // after building response in parsedData:
            byte[] resp = (byte[]) msg.getParsedData().get("response");
            logger.info("TeltonikaHandler: sending DATA ACK ({} bytes)", resp == null ? 0 : resp.length);
            if (ctx != null) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(resp));
            }
            return msg;
        }
        logger.warn("TeltonikaHandler: unrecognized packet, dropping (len={})", data.length);
        throw new ProtocolException("Unsupported Teltonika packet");
    }

    private boolean isDataPacket(byte[] data) {
        if (data == null || data.length < TeltonikaConstants.HEADER_SIZE + 1) { // Min data packet: Preamble (4) + Data Length (4) + Codec (1) + Record Count (1) + CRC (4)
            return false;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Check preamble (4 zero bytes)
            if (buffer.getInt() != 0) {
                return false;
            }

            // Check data length
            int dataLength = buffer.getInt();
            // dataLength is the size from Codec ID to CRC (inclusive of Codec, Record Count, and CRC)
            // Total packet size = Preamble (4) + Data Length (4) + dataLength
            if (data.length < TeltonikaConstants.HEADER_SIZE + dataLength) {
                return false;
            }
            // Add a sanity check for dataLength to prevent extremely large or negative values
            if (dataLength <= 0 || dataLength > 1024 * 1024) { // Reasonable max size, e.g., 1MB
                return false;
            }


            // Check codec ID (should be one of supported codecs)
            int codecId = buffer.get() & 0xFF;
            if (!isSupportedCodec(codecId)) {
                return false;
            }

            // Also check for record count
            if (buffer.remaining() < 1) { // Need at least 1 byte for record count
                return false;
            }
            buffer.get(); // Skip record count, no need to check value here for isDataPacket

            // The remaining bytes should correspond to the dataLength minus what we've already consumed
            // (codec ID (1 byte) + record count (1 byte))
            // This check might be too strict here, as CRC is part of dataLength
            // It's better to rely on dataLength for overall packet size validation
            return true;

        } catch (Exception e) {
            logger.debug("isDataPacket check failed: {}", e.getMessage());
            return false;
        }
    }

    public DeviceMessage handleImeiPacket(byte[] data, DeviceMessage message) throws ProtocolException {
        // Validate packet structure (2 bytes length + IMEI)
        if (data == null || data.length < 17 || data.length > 19) { // 2 bytes length + 15 bytes IMEI = 17. Teltonika spec might allow 18 or 19 with padding.
            throw new ProtocolException("Invalid IMEI packet length");
        }

        int length = ((data[0] & 0xFF) << 8 | (data[1] & 0xFF));
        if (length != IMEI_LENGTH) {  // Teltonika requires exactly 15 digits
            throw new ProtocolException("IMEI length field mismatch. Expected 15, got " + length);
        }
        if (data.length < 2 + length) {
            throw new ProtocolException("IMEI packet too short for advertised length. Expected " + (2+length) + ", got " + data.length);
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
            if (preamble != 0) {
                logger.error("→ Invalid preamble: expected 0x0, got 0x{}", Integer.toHexString(preamble));
                throw new ProtocolException("Invalid preamble");
            }
            logger.info("→ Skipped preamble; value=0x{} ({})",
                    Integer.toHexString(preamble), preamble);

            // 2) Read dataLength (actual packet length)
            int packetLength = buffer.getInt();
            logger.info("→ Read packetLength field={}; will process next {} bytes",
                    packetLength, buffer.remaining());

            // The packetLength is the length of AVL data from Codec ID to CRC.
            // Total expected bytes = HEADER_SIZE (8) + packetLength
            if (data.length < TeltonikaConstants.HEADER_SIZE + packetLength) {
                logger.error("→ Packet too short: totalBytes={} < HEADER_SIZE + packetLength={} ({} + {})",
                        data.length, TeltonikaConstants.HEADER_SIZE + packetLength, TeltonikaConstants.HEADER_SIZE, packetLength);
                throw new ProtocolException("Invalid data length: packet reports " + packetLength + " bytes, but actual remaining is " + (data.length - TeltonikaConstants.HEADER_SIZE));
            }
            // Set a limit on the buffer to only read up to the end of the AVL data (before CRC)
            // The CRC is at the end of the packet after the reported `packetLength` bytes.
            // So, the buffer limit should be current position + packetLength - 4 (for CRC)
            int initialBufferPosition = buffer.position();
            // The dataLength includes Codec ID, Number of Records, AVL data, and CRC.
            // The buffer's current position is after Preamble and Data Length fields.
            // We need to limit the buffer to `packetLength` bytes from its current position
            // to process only the AVL data and then handle the CRC separately.
            // The CRC is the last 4 bytes of the data indicated by packetLength.
            // So, the actual AVL data to read is packetLength - 4 (for CRC).
            buffer.limit(initialBufferPosition + packetLength); // Set limit to the end of the data as indicated by packetLength field

            // 3) Read codec and count
            if (buffer.remaining() < 2) {
                throw new ProtocolException("Not enough bytes for Codec ID and Record Count.");
            }
            int codecId = buffer.get() & 0xFF;
            int recordCount = buffer.get() & 0xFF;
            logger.info("→ codecId={}, recordCount={}", codecId, recordCount);

            // 4) Prepare message
            String version = TeltonikaConstants.CODECS.getOrDefault(codecId, "UNKNOWN");
            message.setProtocolVersion(version);
            message.setMessageType("DATA");

            // 5) Dispatch
            DeviceMessage resultMessage;
            switch (codecId) {
                case CODEC_8:
                case CODEC_8_EXT:
                    resultMessage = processCodec8Packet(buffer, message, recordCount);
                    break;
                case CODEC_16:
                    resultMessage = processCodec16Packet(buffer, message, recordCount);
                    break;
                default:
                    logger.error("→ Unsupported codec: {}", codecId);
                    throw new ProtocolException("Unsupported codec: " + codecId);
            }

            // After processing, the buffer should be at the start of the CRC.
            // The packetLength includes the CRC at the very end.
            // So, consume the CRC bytes for completeness (4 bytes).
            if (buffer.remaining() >= 4) {
                int crc = buffer.getInt(); // Read CRC, but not validating it here
                logger.info("→ Consumed CRC: 0x{}", Integer.toHexString(crc));
            } else {
                logger.warn("→ Missing CRC at the end of the packet. Remaining bytes: {}", buffer.remaining());
                // Depending on validationMode, this could also be a ProtocolException
                if (validationMode == ValidationMode.STRICT) {
                    throw new ProtocolException("Missing CRC at the end of the data packet.");
                }
            }


            return resultMessage;

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

        List<Position> positions = new ArrayList<>();
        int successfulRecords = 0;

        for (int i = 0; i < recordCount; i++) {
            int recordStartPosition = buffer.position();
            logger.info("→ Parsing record #{}/{} starting at buffer position {}", i + 1, recordCount, recordStartPosition);

            // Minimum bytes for one record (fixed part + 1 byte Event ID) = 25 bytes
            // Timestamp (8) + Priority (1) + Lon (4) + Lat (4) + Altitude (2) + Course (2) + Sats (1) + Speed (2) + Event ID (1) = 25
            if (buffer.remaining() < 25) {
                logger.warn("→ Not enough bytes for fixed part + Event ID of record #{} (remaining={}). Skipping remaining records.", i + 1, buffer.remaining());
                break; // Exit loop if not enough bytes for even the fixed part and Event ID
            }
            try {
                Position pos = parseCodec8Data(buffer); // parseCodec8Data now consumes fixed part + Event ID
                logger.info("→ Parsed fixed part of record #{}: ts={}, lat={}, lon={}",
                        i + 1, pos.getTimestamp(), pos.getLatitude(), pos.getLongitude());

                // Now skip the I/O elements for *this* record (starting from I/O counts)
                // The `skipIoElements` method will now handle buffer advancements and potential errors.
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
                successfulRecords++; // Increment only for successfully parsed records

            } catch (ProtocolException ex) {
                logger.warn("→ Failed to parse record #{} due to malformed data: {}", i + 1, ex.getMessage());
                // Attempt to advance the buffer to the end of the reported packet or current record's expected end
                // This recovery mechanism needs to be robust. If an exception occurs, it means
                // the current record is malformed. We should try to skip past it.
                // A safer approach might be to try to jump to where the next record *should* start,
                // or just consume the rest of the buffer if we cannot reliably skip.
                if (validationMode == ValidationMode.STRICT) {
                    throw ex; // Re-throw if in strict mode
                } else {
                    // In lenient or recover mode, try to skip to the end of current expected data.
                    // This is very difficult if we don't know the size of the malformed part.
                    // The safest bet is to consume the rest of the buffer for this packet.
                    // For now, let's just break out of the loop, as we can't trust remaining data.
                    logger.warn("Aborting further record parsing due to unrecoverable error in record #{}.", i+1);
                    buffer.position(buffer.limit()); // Consume rest of the current data for this packet
                    break;
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
        ack.putInt(successfulRecords); // Acknowledge only the records that were successfully processed
        message.addParsedData("response", ack.array());
        logger.info("→ processCodec8Packet: generated ACK for {} records", successfulRecords);

        return message;
    }

    private Position parseCodec8Data(ByteBuffer buffer) throws ProtocolException {
        Position position = new Position();

        // Ensure enough bytes for fixed part + Event ID (25 bytes)
        if (buffer.remaining() < 25) {
            throw new ProtocolException("Not enough bytes for fixed AVL data and Event ID. Remaining: " + buffer.remaining());
        }

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

        // --- Event ID (1 byte) ---
        int eventId = buffer.get() & 0xFF;
        logger.debug("→ parseCodec8Data: skipped Event ID={}", eventId);

        return position;
    }


    private DeviceMessage processCodec16Packet(ByteBuffer buffer, DeviceMessage message, int recordCount) throws ProtocolException {
        logger.info("→ Entered processCodec16Packet; buffer.position={}, remainingBytes={}",
                buffer.position(), buffer.remaining());

        List<Position> positions = new ArrayList<>();
        int successfulRecords = 0;

        for (int i = 0; i < recordCount; i++) {
            int recordStartPosition = buffer.position();
            logger.info("→ Parsing Codec16 record #{}/{} starting at buffer position {}", i + 1, recordCount, recordStartPosition);

            // Minimum bytes for one record (fixed part + 1 byte Event ID) - same as Codec8 for base fields + event ID
            if (buffer.remaining() < 25) { // Assuming 25 bytes fixed part + Event ID for Codec16
                logger.warn("→ Not enough bytes for fixed part + Event ID of Codec16 record #{} (remaining={}). Skipping remaining records.", i + 1, buffer.remaining());
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
                successfulRecords++;
            } catch (ProtocolException e) {
                logger.warn("Failed to parse Codec16 record #{} due to malformed data: {}", i + 1, e.getMessage());
                if (validationMode == ValidationMode.STRICT) {
                    throw e; // Re-throw if in strict mode
                } else {
                    logger.warn("Aborting further record parsing due to unrecoverable error in Codec16 record #{}.", i+1);
                    buffer.position(buffer.limit()); // Consume rest of the current data for this packet
                    break;
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
        response.putInt(successfulRecords); // Acknowledge only the records that were successfully processed
        message.addParsedData("response", response.array());

        return message;
    }

    private Position parseCodec16Data(ByteBuffer buffer) throws ProtocolException {
        // This method is called by parsePosition directly when only one position is expected.
        // It needs to handle skipping I/O elements itself in this context.
        Position position = parseCodec8Data(buffer); // Parse fixed part + Event ID

        skipIoElements(buffer, CODEC_16); // Skip I/O elements for this single record

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

        // Alternative 8-byte heartbeat format (not standard for Teltonika but can be seen)
        if (data.length == 8) {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            return buffer.getInt() == 0 && buffer.getInt() == 0;
        }

        return false;
    }

    private boolean isImeiPacket(byte[] data) {
        // IMEI packet starts with a 2-byte length field, followed by the IMEI.
        // The length field should indicate the length of the IMEI string (15 bytes).
        // Total expected length for a valid IMEI packet: 2 (length field) + 15 (IMEI) = 17 bytes.
        if (data == null || data.length < 2) {
            return false;
        }
        int length = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        return length == IMEI_LENGTH && data.length == (2 + IMEI_LENGTH);
    }

    private void validateCoordinates(double latitude, double longitude) throws ProtocolException {
        // The original line below is commented out to ignore invalid coordinates
        // if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
        //     throw new ProtocolException("Invalid coordinates: lat=" + latitude + ", lon=" + longitude);
        // }
    }

    // Corrected skipIoElements to robustly handle byte consumption
    private void skipIoElements(ByteBuffer buffer, int codecId) throws ProtocolException {
        int beforeAll = buffer.position();
        int[] sizes = {1, 2, 4, 8}; // For Codec 8, only 1, 2, 4 bytes are common, but 8 is for Codec8 Extended.
        // For Codec 16, typically 1, 2, 4, 8 bytes are used.
        for (int size : sizes) {
            // For CODEC_8, 8-byte I/O properties are part of CODEC_8_EXT.
            // If it's pure CODEC_8, skip 8-byte I/O sections.
            if (size == 8 && codecId == CODEC_8) continue; // Skip 8-byte for CODEC_8, only consider for CODEC_8_EXT or CODEC_16

            // Check if there's at least one byte for the count
            if (buffer.remaining() < 1) {
                logger.warn("→ Missing count byte for {}-byte I/O group. Remaining: {}. Exiting I/O skipping.", size, buffer.remaining());
                // This means the I/O data is truncated severely.
                // We cannot reliably parse further I/O elements.
                throw new ProtocolException("Truncated I/O data: missing count for " + size + "-byte group.");
            }
            int count = buffer.get() & 0xFF; // Read the count for this I/O element size
            int beforeGroup = buffer.position();

            logger.info("→ I/O group {}-byte: count = {}", size, count);

            // Calculate expected bytes for this group
            long expectedBytesForGroup = (long) count * (1 + size); // 1 byte for ID + size bytes for value

            // Crucial check: Ensure enough bytes are available for ALL elements in this group
            if (buffer.remaining() < expectedBytesForGroup) {
                logger.warn("Not enough bytes for {}-byte I/O elements (expected {} bytes for {} elements, but only {} remaining). " +
                                "This indicates malformed data. Skipping to end of buffer for this packet.",
                        size, expectedBytesForGroup, count, buffer.remaining());
                // Advance buffer to its limit (end of the packet data area)
                buffer.position(buffer.limit());
                throw new ProtocolException("Malformed I/O data: truncated " + size + "-byte I/O elements.");
            }

            // Skip each element: 1-byte ID + `size`-byte payload
            for (int i = 0; i < count; i++) {
                buffer.get(); // Skip ID byte
                buffer.position(buffer.position() + size); // Skip payload bytes
            }

            int afterGroup = buffer.position();
            int consumed = afterGroup - beforeGroup;
            logger.info(
                    "   → Group {}-byte: expected to skip {} bytes, actually skipped {} bytes",
                    size, expectedBytesForGroup, consumed
            );
        }
        int afterAll = buffer.position();
        logger.info("→ skipIoElements: total consumed = {} bytes", afterAll - beforeAll);
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
            if ((imei.length() - i) % 2 == 0) { // Double every other digit starting from the right (0-indexed)
                // For 15-digit IMEI, this means 0, 2, 4, 6, 8, 10, 12, 14
                // Or, if working from left, double 1st, 3rd, 5th, etc.
                // Luhn algorithm usually doubles every second digit from the right.
                // For 0-indexed string, it's (length - 1 - i) % 2 == 1 or (i % 2 != 0) if doubling second digit from left.
                // Re-evaluating: standard Luhn often processes right-to-left.
                // Let's assume the existing (i % 2 != 0) was for 0-indexed string from left, doubling 2nd, 4th, 6th etc.
                // This is typical for implementations that iterate left-to-right.
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
        // Updated to explicitly accept "1.0" for the initial detection phase
        return "TELTONIKA".equalsIgnoreCase(protocol) &&
                (version == null || version.startsWith("CODEC") || "1.0".equalsIgnoreCase(version));
    }


    public enum ValidationMode {
        STRICT, LENIENT, RECOVER
    }
}