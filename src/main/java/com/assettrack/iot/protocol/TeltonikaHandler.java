package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey; // Corrected import for AttributeKey
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
    // Corrected Heartbeat ACK to a single byte 0x01
    private static final byte[] HEARTBEAT_RESPONSE = new byte[] {0x01};

    @Value("${teltonika.validation.mode:STRICT}")
    private ValidationMode validationMode;

    // This method signature is from the ProtocolHandler interface for parsing raw message
    // It's not directly used for the network stream handling as seen in handle(byte[] data, ChannelHandlerContext ctx)
    @Override
    public Position parsePosition(byte[] rawMessage) throws ProtocolException {
        if (rawMessage == null || rawMessage.length < TeltonikaConstants.HEADER_SIZE) { // Assuming TeltonikaConstants.HEADER_SIZE is 8
            throw new ProtocolException("Message too short or null");
        }

        ByteBuffer buffer = ByteBuffer.wrap(rawMessage).order(ByteOrder.BIG_ENDIAN);

        try {
            // Validate packet structure
            if (buffer.getInt() != 0) { // Preamble
                throw new ProtocolException("Invalid preamble");
            }

            final int dataLength = buffer.getInt();
            // dataLength is from Codec ID to CRC. Total expected length is HEADER_SIZE + dataLength.
            if (rawMessage.length < TeltonikaConstants.HEADER_SIZE + dataLength) {
                throw new ProtocolException("Invalid data length: packet reports " + dataLength + " bytes, but actual total is " + rawMessage.length);
            }
            // Set buffer limit to the end of the AVL data block (before CRC)
            // The dataLength includes CRC, so actual AVL data bytes = dataLength - 4 (for CRC)
            buffer.limit(TeltonikaConstants.HEADER_SIZE + dataLength - 4); // Limit to end of AVL data, before CRC

            final int codecId = buffer.get() & 0xFF;
            if (!isSupportedCodec(codecId)) {
                throw new ProtocolException("Unsupported codec: " + codecId);
            }

            // Parse IMEI (this part seems out of place for `parsePosition` if IMEI is in separate packet)
            // This suggests parsePosition is intended for a full AVL packet with IMEI prefix.
            // If the IMEI is part of the AVL data (Codec 12), the parsing would be different.
            // Assuming this parsePosition is for Codec 8/8E/16 where IMEI is handled by parent handler.
            // Removed IMEI parsing here as it's handled in isImeiPacket and handleImeiPacket,
            // and positions are typically associated with an IMEI from the session.
            // If this method is called directly with a raw message containing IMEI, it needs revision.

            // The following IMEI handling is problematic if parsePosition is called on an actual AVL data packet
            // where IMEI is not expected at the beginning of the buffer AFTER preamble and dataLength.
            // Commenting out to avoid conflict with standard Teltonika AVL data structure.
            /*
            byte[] imeiBytes = new byte[IMEI_LENGTH];
            buffer.get(imeiBytes);
            String imei = cleanImei(new String(imeiBytes, StandardCharsets.US_ASCII));

            if (!isValidImei(imei)) {
                throw new ProtocolException("Invalid IMEI: " + imei);
            }
            Device device = new Device();
            device.setImei(imei);
            device.setProtocolType("TELTONIKA");
            */

            // Parse position data based on codec
            Position position;
            switch (codecId) {
                case CODEC_8:
                case CODEC_8_EXT:
                    // For parsePosition, we usually expect a single record.
                    // The buffer should contain just one record's data.
                    position = parseCodec8Data(buffer);
                    break;
                case CODEC_16:
                    position = parseCodec16Data(buffer); // This calls parseCodec8Data then skipIoElements
                    break;
                default:
                    throw new ProtocolException("Unhandled codec: " + codecId);
            }

            // After parsing AVL data, advance buffer past CRC (which is part of dataLength)
            // The CRC is the last 4 bytes of the packetLength field's data.
            // We set limit to before CRC. Now we need to consume the CRC.
            buffer.position(rawMessage.length - 4); // Move to CRC position
            final int crc = buffer.getInt(); // Read CRC
            logger.debug("CRC (rawMessage parse): 0x{}", Integer.toHexString(crc));

            // position.setDevice(device); // Device association should happen at higher level, from session context.
            return position;

        } catch (Exception e) {
            throw new ProtocolException("Failed to parse position: " + e.getMessage(), e);
        }
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        return handle(data, null); // Delegate to the method with ChannelHandlerContext, passing null
    }

    /** Convert a byte array to a hex string (uppercase, no separators). */
    private static String toHexString(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public DeviceMessage handle(final byte[] data, final ChannelHandlerContext ctx) throws ProtocolException {
        logger.info(
                "→ TeltonikaHandler.handle(...) called; data.length={}, ctx={}",
                data.length,
                ctx
        );
        final DeviceMessage message = new DeviceMessage();
        message.setProtocol("TELTONIKA");

        logger.info("TeltonikaHandler: ENTER handle, data.length={}, firstBytes={}", data.length,
                data.length > 4 ? String.format("%02X%02X%02X%02X", data[0],data[1],data[2],data[3]) : toHexString(data));
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
            final DeviceMessage msg = handleImeiPacket(data, message);
            if (ctx != null) {
                // Store IMEI in channel context for subsequent AVL data packets
                ctx.channel().attr(AttributeKey.valueOf("imei")).set(msg.getImei());
                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x01})) // Standard IMEI ACK is 0x01
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
            final DeviceMessage msg = handleDataPacket(data, message);
            // After building response in parsedData:
            final byte[] resp = (byte[]) msg.getParsedData().get("response");
            logger.info("TeltonikaHandler: sending DATA ACK ({} bytes)", resp == null ? 0 : resp.length);
            if (ctx != null) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(resp));
            }
            return msg;
        }
        logger.warn("TeltonikaHandler: unrecognized packet, dropping (len={})", data.length);
        throw new ProtocolException("Unsupported Teltonika packet");
    }

    private boolean isDataPacket(final byte[] data) {
        // Min data packet: Preamble (4) + Data Length (4) + Codec (1) + Record Count (1) + CRC (4) = 14 bytes
        if (data == null || data.length < TeltonikaConstants.HEADER_SIZE + 1 + 1 + 4) { // HEADER_SIZE is 8 for preamble+length
            return false;
        }

        try {
            final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Check preamble (4 zero bytes)
            if (buffer.getInt() != 0) {
                return false;
            }

            // Check data length
            final int dataLength = buffer.getInt();
            // dataLength is the size from Codec ID to CRC (inclusive of Codec, Record Count, and CRC)
            // Total packet size = Preamble (4) + Data Length (4) + dataLength
            if (data.length < TeltonikaConstants.HEADER_SIZE + dataLength) {
                logger.debug("isDataPacket: data length mismatch. Expected at least {} but got {}", TeltonikaConstants.HEADER_SIZE + dataLength, data.length);
                return false;
            }
            // Add a sanity check for dataLength to prevent extremely large or negative values
            if (dataLength <= 0 || dataLength > 1024 * 1024) { // Reasonable max size, e.g., 1MB
                logger.debug("isDataPacket: invalid dataLength. Value: {}", dataLength);
                return false;
            }

            // Check codec ID (should be one of supported codecs)
            final int codecId = buffer.get() & 0xFF;
            if (!isSupportedCodec(codecId)) {
                logger.debug("isDataPacket: unsupported codec ID. Value: {}", codecId);
                return false;
            }

            // Also check for record count
            if (buffer.remaining() < 1) { // Need at least 1 byte for record count
                logger.debug("isDataPacket: missing record count byte.");
                return false;
            }
            buffer.get(); // Skip record count, no need to check value here for isDataPacket

            return true;

        } catch (final Exception e) {
            logger.debug("isDataPacket check failed: {}", e.getMessage());
            return false;
        }
    }

    public DeviceMessage handleImeiPacket(final byte[] data, final DeviceMessage message) throws ProtocolException {
        // Validate packet structure: 2 bytes length + IMEI.
        // The length field should indicate the length of the IMEI string (15 bytes).
        // Total expected length for a valid IMEI packet: 2 (length field) + 15 (IMEI) = 17 bytes.
        if (data == null || data.length < 2) {
            throw new ProtocolException("Invalid IMEI packet: data too short for length field.");
        }

        final int length = ((data[0] & 0xFF) << 8 | (data[1] & 0xFF));
        if (length != IMEI_LENGTH) {
            throw new ProtocolException("IMEI length field mismatch. Expected " + IMEI_LENGTH + ", got " + length);
        }
        if (data.length != (2 + IMEI_LENGTH)) { // Strict check for 17 bytes total
            throw new ProtocolException("IMEI packet has incorrect total length. Expected " + (2 + IMEI_LENGTH) + ", got " + data.length);
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


    private DeviceMessage handleDataPacket(final byte[] data, final DeviceMessage message) throws ProtocolException {
        try {
            logger.info("→ Entered handleDataPacket; totalBytes={}", data.length);
            final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            logger.info("→ Buffer wrapped; remainingBytes={}", buffer.remaining());

            // 1) Skip Teltonika “preamble” (always zero)
            final int preamble = buffer.getInt();
            if (preamble != 0) {
                logger.error("→ Invalid preamble: expected 0x0, got 0x{}", Integer.toHexString(preamble));
                throw new ProtocolException("Invalid preamble");
            }
            logger.info("→ Skipped preamble; value=0x{} ({})",
                    Integer.toHexString(preamble), preamble);

            // 2) Read dataLength (actual packet length)
            final int packetLength = buffer.getInt();
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
            // The CRC is the last 4 bytes of the data indicated by packetLength.
            // So, the actual AVL data to read is packetLength - 4 (for CRC).
            final int avlDataLimit = buffer.position() + packetLength - 4;
            if (avlDataLimit < buffer.position() || avlDataLimit > buffer.limit()) { // Sanity check for limit calculation
                throw new ProtocolException("Calculated AVL data limit is invalid: " + avlDataLimit);
            }
            buffer.limit(avlDataLimit);

            // 3) Read codec and count
            if (buffer.remaining() < 2) {
                throw new ProtocolException("Not enough bytes for Codec ID and Record Count.");
            }
            final int codecId = buffer.get() & 0xFF;
            final int recordCount = buffer.get() & 0xFF;
            logger.info("→ codecId={}, recordCount={}", codecId, recordCount);

            // 4) Prepare message
            final String version = TeltonikaConstants.CODECS.getOrDefault(codecId, "UNKNOWN");
            message.setProtocolVersion(version);
            message.setMessageType("DATA");

            // 5) Dispatch
            final DeviceMessage resultMessage;
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
            // Restore original limit to read CRC.
            buffer.limit(buffer.capacity()); // Reset limit to full buffer capacity
            // Position buffer to read CRC which is at the original `initialBufferPosition + packetLength`
            buffer.position(TeltonikaConstants.HEADER_SIZE + packetLength - 4); // Position to CRC start
            if (buffer.remaining() >= 4) {
                final int crc = buffer.getInt(); // Read CRC
                logger.info("→ Consumed CRC: 0x{}", Integer.toHexString(crc));
            } else {
                logger.warn("→ Missing CRC at the end of the packet. Remaining bytes: {}", buffer.remaining());
                if (validationMode == ValidationMode.STRICT) {
                    throw new ProtocolException("Missing CRC at the end of the data packet.");
                }
            }
            // Ensure buffer position is at the end of the consumed packet to avoid re-reading
            buffer.position(TeltonikaConstants.HEADER_SIZE + packetLength);

            return resultMessage;

        } catch (Exception e) {
            logger.error("→ Error handling Teltonika data packet", e);
            message.setMessageType("ERROR");
            message.addParsedData("error", e.getMessage());

            // Correction: Handle exceptions based on validationMode
            if (validationMode == ValidationMode.LENIENT) {
                // In strict mode, re-throw the exception for any parsing error
                throw new ProtocolException("Failed to handle data packet", e);
            } else {
                // In lenient or recover mode, log the error but allow partial processing if any, and return message.
                // The message object itself might contain partial data (e.g., IMEI from earlier stages or some positions)
                // if the error occurred after some processing.
                logger.warn("→ Packet parsing failed in non-STRICT mode. Returning partially processed message if available. Error: {}", e.getMessage());
                return message; // Return the message object, even if partial or error-marked
            }
        }
    }


    private DeviceMessage processCodec8Packet(
            final ByteBuffer buffer,
            final DeviceMessage message,
            final int recordCount) throws ProtocolException {

        logger.info("→ Entered processCodec8Packet; buffer.position={}, remainingBytes={}",
                buffer.position(), buffer.remaining());

        final List<Position> positions = new ArrayList<>();
        int successfulRecords = 0;

        for (int i = 0; i < recordCount; i++) {
            final int recordStartPosition = buffer.position();
            logger.info("→ Parsing record #{}/{} starting at buffer position {}", i + 1, recordCount, recordStartPosition);

            // Minimum bytes for one record (fixed part + 1 byte Event ID) = 25 bytes
            // Timestamp (8) + Priority (1) + Lon (4) + Lat (4) + Altitude (2) + Course (2) + Sats (1) + Speed (2) + Event ID (1) = 25
            if (buffer.remaining() < 25) {
                logger.warn("→ Not enough bytes for fixed part + Event ID of record #{} (remaining={}). Skipping remaining records.", i + 1, buffer.remaining());
                break; // Exit loop if not enough bytes for even the fixed part and Event ID
            }
            try {
                final Position pos = parseCodec8Data(buffer); // parseCodec8Data now consumes fixed part + Event ID
                logger.info("→ Parsed fixed part of record #{}: ts={}, lat={}, lon={}",
                        i + 1, pos.getTimestamp(), pos.getLatitude(), pos.getLongitude());

                // Now skip the I/O elements for *this* record (starting from I/O counts)
                // The `skipIoElements` method will now handle buffer advancements and potential errors.
                skipIoElements(buffer, CODEC_8);
                logger.info("→ Skipped I/O elements for record #{}; new buffer position={}", i + 1, buffer.position());

                // associate device
                if (message.getImei() != null) {
                    final Device d = new Device();
                    d.setImei(message.getImei());
                    d.setProtocolType("TELTONIKA");
                    pos.setDevice(d);
                }

                positions.add(pos);
                successfulRecords++; // Increment only for successfully parsed records

            } catch (final ProtocolException ex) {
                logger.warn("→ Failed to parse record #{} due to malformed data: {}", i + 1, ex.getMessage());
                if (validationMode == ValidationMode.STRICT) {
                    throw ex; // Re-throw if in strict mode
                } else {
                    // In lenient or recover mode, try to skip to the end of current expected data.
                    // This is very difficult if we don't know the size of the malformed part.
                    // The safest bet is to consume the rest of the buffer for this packet's AVL data.
                    logger.warn("Aborting further record parsing due to unrecoverable error in record #{}. Consuming remaining bytes in current AVL data block.", i+1);
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
        final ByteBuffer ack = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN); // Allocate only 4 bytes
        ack.putInt(successfulRecords); // Acknowledge only the records that were successfully processed
        message.addParsedData("response", ack.array());
        logger.info("→ processCodec8Packet: generated ACK for {} records", successfulRecords);

        return message;
    }

    private Position parseCodec8Data(final ByteBuffer buffer) throws ProtocolException {
        final Position position = new Position();

        // Ensure enough bytes for fixed part + Event ID (25 bytes)
        if (buffer.remaining() < 25) {
            throw new ProtocolException("Not enough bytes for fixed AVL data and Event ID. Remaining: " + buffer.remaining());
        }

        // 1) Timestamp (8 bytes)
        final long ts = buffer.getLong();
        position.setTimestamp(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.of("UTC")) // Use UTC for timestamps from device
        );

        // 2) Priority (1 byte) — drop
        final int priority = buffer.get() & 0xFF;
        logger.debug("→ parseCodec8Data: priority={}", priority);

        // 3) Coordinates: LONG first, then LAT (each 4 bytes, scaled 1e7)
        final int lonRaw = buffer.getInt();
        final int latRaw = buffer.getInt();
        final double longitude = lonRaw / 1e7;
        final double latitude  = latRaw / 1e7;
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        logger.info("→ parseCodec8Data: lat={}, lon={}", latitude, longitude);

        // 4) Altitude (2 bytes)
        position.setAltitude(buffer.getShort());

        // 5) Course (2 bytes)
        position.setCourse((double)(buffer.getShort() & 0xFFFF));

        // 6) Satellites & validity
        final int sats = buffer.get() & 0xFF;
        position.setValid(sats > 0);

        // 7) Speed (2 bytes, knots → km/h)
        final double speedKnots = buffer.getShort() & 0xFFFF;
        position.setSpeed(speedKnots * 1.852);

        // --- Event ID (1 byte) ---
        final int eventId = buffer.get() & 0xFF;
        logger.debug("→ parseCodec8Data: skipped Event ID={}", eventId);

        return position;
    }


    private DeviceMessage processCodec16Packet(final ByteBuffer buffer, final DeviceMessage message, final int recordCount) throws ProtocolException {
        logger.info("→ Entered processCodec16Packet; buffer.position={}, remainingBytes={}",
                buffer.position(), buffer.remaining());

        final List<Position> positions = new ArrayList<>();
        int successfulRecords = 0;

        for (int i = 0; i < recordCount; i++) {
            final int recordStartPosition = buffer.position();
            logger.info("→ Parsing Codec16 record #{}/{} starting at buffer position {}", i + 1, recordCount, recordStartPosition);

            // Minimum bytes for one record (fixed part + 1 byte Event ID) - same as Codec8 for base fields + event ID
            if (buffer.remaining() < 25) { // Assuming 25 bytes fixed part + Event ID for Codec16
                logger.warn("→ Not enough bytes for fixed part + Event ID of Codec16 record #{} (remaining={}). Skipping remaining records.", i + 1, buffer.remaining());
                break;
            }

            try {
                // Use the same parsing as Codec8 for base fields + Event ID
                final Position position = parseCodec8Data(buffer); // Now parses fixed part + Event ID
                logger.info("→ Parsed fixed part of Codec16 record #{}: ts={}, lat={}, lon={}",
                        i + 1, position.getTimestamp(), position.getLatitude(), position.getLongitude());

                // Handle Codec16 specific fields - this primarily means skipping I/O elements
                // The `skipIoElements` method will now correctly start from the I/O counts.
                skipIoElements(buffer, CODEC_16);
                logger.info("→ Skipped I/O elements for Codec16 record #{}; new buffer position={}", i + 1, buffer.position());

                if (message.getImei() != null) {
                    final Device d = new Device();
                    d.setImei(message.getImei());
                    d.setProtocolType("TELTONIKA");
                    position.setDevice(d);
                }
                positions.add(position);
                successfulRecords++;
            } catch (final ProtocolException e) {
                logger.warn("Failed to parse Codec16 record #{} due to malformed data: {}", i + 1, e.getMessage());
                if (validationMode == ValidationMode.STRICT) {
                    throw e; // Re-throw if in strict mode
                } else {
                    logger.warn("Aborting further record parsing due to unrecoverable error in Codec16 record #{}. Consuming remaining bytes in current AVL data block.", i+1);
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
        final ByteBuffer response = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        response.putInt(successfulRecords); // Acknowledge only the records that were successfully processed
        message.addParsedData("response", response.array());

        return message;
    }

    // This method is called by parsePosition directly when only one position is expected.
    private Position parseCodec16Data(final ByteBuffer buffer) throws ProtocolException {
        final Position position = parseCodec8Data(buffer); // Parse fixed part + Event ID
        skipIoElements(buffer, CODEC_16); // Skip I/O elements for this single record
        return position;
    }

    private DeviceMessage handleHeartbeat() {
        final DeviceMessage message = new DeviceMessage();
        message.setProtocol("TELTONIKA");
        message.setMessageType("HEARTBEAT");
        message.addParsedData("response", HEARTBEAT_RESPONSE);
        logger.debug("Responded to heartbeat");
        return message;
    }

    private boolean isHeartbeatPacket(final byte[] data) {
        if (data == null) return false;

        // Standard 4-byte null heartbeat
        if (data.length == 4) {
            return data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 0;
        }

        // Alternative 8-byte heartbeat format (not standard for Teltonika but can be seen)
        if (data.length == 8) {
            final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            return buffer.getInt() == 0 && buffer.getInt() == 0;
        }

        return false;
    }

    private boolean isImeiPacket(final byte[] data) {
        // IMEI packet starts with a 2-byte length field, followed by the IMEI.
        // The length field should indicate the length of the IMEI string (15 bytes).
        // Total expected length for a valid IMEI packet: 2 (length field) + 15 (IMEI) = 17 bytes.
        if (data == null || data.length < 2) {
            return false;
        }
        final int length = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        // Strict check: length field must be IMEI_LENGTH and total data length must match 2 + IMEI_LENGTH
        return length == IMEI_LENGTH && data.length == (2 + IMEI_LENGTH);
    }

    // Removed validateCoordinates as it was commented out and not used

    // Corrected skipIoElements to robustly handle byte consumption
    private void skipIoElements(final ByteBuffer buffer, final int codecId) throws ProtocolException {
        final int beforeAll = buffer.position();
        final int[] sizes = {1, 2, 4, 8}; // For Codec 8, only 1, 2, 4 bytes are common, but 8 is for Codec8 Extended.
        // For Codec 16, typically 1, 2, 4, 8 bytes are used.
        for (final int size : sizes) {
            // For CODEC_8, 8-byte I/O properties are part of CODEC_8_EXT.
            // If it's pure CODEC_8, skip 8-byte I/O sections.
            if (size == 8 && codecId == CODEC_8) continue; // Skip 8-byte for CODEC_8, only consider for CODEC_8_EXT or CODEC_16

            // Check if there's at least one byte for the count
            if (buffer.remaining() < 1) {
                logger.warn("→ Missing count byte for {}-byte I/O group. Remaining: {}. Exiting I/O skipping.", size, buffer.remaining());
                throw new ProtocolException("Truncated I/O data: missing count for " + size + "-byte group.");
            }
            final int count = buffer.get() & 0xFF; // Read the count for this I/O element size
            final int beforeGroup = buffer.position();

            logger.info("→ I/O group {}-byte: count = {}", size, count);

            // Calculate expected bytes for this group
            final long expectedBytesForGroup = (long) count * (1 + size); // 1 byte for ID + size bytes for value

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
                // Directly advance position for payload to avoid multiple method calls
                buffer.position(buffer.position() + size);
            }

            final int afterGroup = buffer.position();
            final int consumed = afterGroup - beforeGroup;
            logger.info(
                    "   → Group {}-byte: expected to skip {} bytes, actually skipped {} bytes",
                    size, expectedBytesForGroup, consumed
            );
        }
        final int afterAll = buffer.position();
        logger.info("→ skipIoElements: total consumed = {} bytes", afterAll - beforeAll);
    }

    private String cleanImei(final String rawImei) {
        return rawImei != null ? rawImei.replaceAll("[^0-9]", "") : "";
    }

    private boolean isValidImei(final String imei) {
        if (imei == null || imei.length() != 15 || !IMEI_PATTERN.matcher(imei).matches()) {
            return false;
        }

        // Luhn algorithm check
        // Sum of digits, doubling every second digit from the right.
        // For a 0-indexed string, iterating left-to-right, and a 15-digit IMEI (odd length),
        // we double digits at indices 0, 2, 4, 6, 8, 10, 12.
        int sum = 0;
        for (int i = 0; i < imei.length(); i++) {
            int digit = Character.getNumericValue(imei.charAt(i));
            if ((imei.length() - 1 - i) % 2 == 1) { // Check if it's an 'every second' digit from the right, starting second to last
                // This means (i % 2 == 0) for odd length string if iterating left to right
                digit *= 2;
                if (digit > 9) {
                    digit = (digit % 10) + 1; // Sum the digits if doubling resulted in a two-digit number
                }
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }

    private boolean isSupportedCodec(final int codecId) {
        return codecId == CODEC_8 || codecId == CODEC_8_EXT || codecId == CODEC_16;
    }

    @Override
    public byte[] generateResponse(final Position position) {
        // This method is typically for single-position responses, not the data packet ACK.
        // It should also return a 4-byte count if used for data acknowledgments.
        final ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(1); // Acknowledge 1 record
        return buffer.array();
    }

    @Override
    public boolean supports(final String protocolType) {
        return "TELTONIKA".equalsIgnoreCase(protocolType);
    }


    @Override
    public boolean canHandle(final String protocol, final String version) {
        // Updated to explicitly accept "1.0" for the initial detection phase
        return "TELTONIKA".equalsIgnoreCase(protocol) &&
                (version == null || version.startsWith("CODEC") || "1.0".equalsIgnoreCase(version));
    }


    public enum ValidationMode {
        STRICT, LENIENT, RECOVER
    }

    // Assuming TeltonikaConstants is a separate class with common constants
    public static class TeltonikaConstants {
        public static final int HEADER_SIZE = 8; // Preamble (4 bytes) + Data Length (4 bytes)
        public static final Map<Integer, String> CODECS = Map.of(
                0x08, "CODEC8",
                0x8E, "CODEC8_EXT",
                0x10, "CODEC16"
        );
    }
}