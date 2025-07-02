package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.repository.DeviceRepository;
import com.assettrack.iot.repository.PositionRepository;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
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
import java.util.Optional;
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
    private static final byte[] HEARTBEAT_RESPONSE = new byte[]{0x01};

    @Value("${teltonika.validation.mode:LENIENT}")
    private ValidationMode validationMode;

    private final DeviceRepository deviceRepository;
    private final PositionRepository positionRepository;

    public TeltonikaHandler(DeviceRepository deviceRepository, PositionRepository positionRepository) {
        this.deviceRepository = deviceRepository;
        this.positionRepository = positionRepository;
    }

    @Override
    public Position parsePosition(byte[] rawMessage) throws ProtocolException {
        if (rawMessage == null || rawMessage.length < TeltonikaConstants.HEADER_SIZE) {
            throw new ProtocolException("Message too short or null");
        }

        ByteBuffer buffer = ByteBuffer.wrap(rawMessage).order(ByteOrder.BIG_ENDIAN);

        try {
            if (buffer.getInt() != 0) {
                throw new ProtocolException("Invalid preamble");
            }

            final int dataLength = buffer.getInt();
            if (rawMessage.length < TeltonikaConstants.HEADER_SIZE + dataLength) {
                throw new ProtocolException("Invalid data length: packet reports " + dataLength + " bytes, but actual total is " + rawMessage.length);
            }
            buffer.limit(TeltonikaConstants.HEADER_SIZE + dataLength - 4);

            final int codecId = buffer.get() & 0xFF;
            if (!isSupportedCodec(codecId)) {
                throw new ProtocolException("Unsupported codec: " + codecId);
            }

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

            buffer.position(rawMessage.length - 4);
            final int crc = buffer.getInt();
            logger.debug("CRC (rawMessage parse): 0x{}", Integer.toHexString(crc));

            return position;

        } catch (Exception e) {
            throw new ProtocolException("Failed to parse position: " + e.getMessage(), e);
        }
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        return handle(data, null);
    }

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
        logger.info("TeltonikaHandler: ENTER handle, data.length={}, firstBytes={}", data.length,
                data.length > 4 ? String.format("%02X%02X%02X%02X", data[0], data[1], data[2], data[3]) : toHexString(data));
        logger.info("TeltonikaHandler: ENTER handle, data.length={}, first4={}",
                data.length,
                data.length >= 4 ? toHexString(Arrays.copyOf(data, 4)) : toHexString(data));


        final DeviceMessage message = new DeviceMessage();
        message.setProtocol("TELTONIKA");

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
            final DeviceMessage msg = handleImeiPacket(data, message, ctx);
            byte[] resp = (byte[]) msg.getParsedData().get("response");
            if (resp == null) {
                resp = new byte[]{0x01};
                msg.addParsedData("response", resp);
            }
            if (ctx != null) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(resp))
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
            Device device = null;
            if (ctx != null && ctx.channel().hasAttr(AttributeKey.valueOf("device"))) {
                device = (Device) ctx.channel().attr(AttributeKey.valueOf("device")).get();
            }

            final DeviceMessage msg = handleDataPacket(data, message, device);
            byte[] resp = (byte[]) msg.getParsedData().get("response");
            if (resp == null) {
                resp = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array();
                msg.addParsedData("response", resp);
            }
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
        if (data == null || data.length < TeltonikaConstants.HEADER_SIZE + 1 + 1 + 4) {
            return false;
        }

        try {
            final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            if (buffer.getInt() != 0) {
                return false;
            }

            final int dataLength = buffer.getInt();
            if (data.length < TeltonikaConstants.HEADER_SIZE + dataLength) {
                logger.debug("isDataPacket: data length mismatch. Expected at least {} but got {}", TeltonikaConstants.HEADER_SIZE + dataLength, data.length);
                return false;
            }
            if (dataLength <= 0 || dataLength > 1024 * 1024) {
                logger.debug("isDataPacket: invalid dataLength. Value: {}", dataLength);
                return false;
            }

            final int codecId = buffer.get() & 0xFF;
            if (!isSupportedCodec(codecId)) {
                logger.debug("isDataPacket: unsupported codec ID. Value: {}", codecId);
                return false;
            }

            if (buffer.remaining() < 1) {
                logger.debug("isDataPacket: missing record count byte.");
                return false;
            }
            buffer.get();

            return true;

        } catch (final Exception e) {
            logger.debug("isDataPacket check failed: {}", e.getMessage());
            return false;
        }
    }

    public DeviceMessage handleImeiPacket(final byte[] data, final DeviceMessage message, final ChannelHandlerContext ctx) throws ProtocolException {
        if (data == null || data.length < 2) {
            throw new ProtocolException("Invalid IMEI packet: data too short for length field.");
        }

        final int length = ((data[0] & 0xFF) << 8 | (data[1] & 0xFF));
        if (length != IMEI_LENGTH) {
            throw new ProtocolException("IMEI length field mismatch. Expected " + IMEI_LENGTH + ", got " + length);
        }
        if (data.length != (2 + IMEI_LENGTH)) {
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

        Optional<Device> existingDevice = deviceRepository.findByImei(imei);
        Device device;
        if (existingDevice.isPresent()) {
            device = existingDevice.get();
            logger.info("Found existing device for IMEI: {}", imei);
        } else {
            device = new Device();
            device.setImei(imei);
            device.setProtocolType("TELTONIKA");
            device.setName("Teltonika Device - " + imei);
            deviceRepository.save(device);
            logger.info("Created new device for IMEI: {}", imei);
        }

        if (ctx != null) {
            ctx.channel().attr(AttributeKey.valueOf("device")).set(device);
        }

        return message;
    }


    private DeviceMessage handleDataPacket(final byte[] data, final DeviceMessage message, final Device device) throws ProtocolException {
        try {
            if (device == null) {
                throw new ProtocolException("Device not associated with channel session for data packet.");
            }

            logger.info("→ Entered handleDataPacket; totalBytes={}", data.length);
            final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            logger.info("→ Buffer wrapped; remainingBytes={}", buffer.remaining());

            final int preamble = buffer.getInt();
            if (preamble != 0) {
                logger.error("→ Invalid preamble: expected 0x0, got 0x{}", Integer.toHexString(preamble));
                throw new ProtocolException("Invalid preamble");
            }
            logger.info("→ Skipped preamble; value=0x{} ({})",
                    Integer.toHexString(preamble), preamble);

            final int packetLength = buffer.getInt();
            logger.info("→ Read packetLength field={}; will process next {} bytes",
                    packetLength, buffer.remaining());

            if (data.length < TeltonikaConstants.HEADER_SIZE + packetLength) {
                logger.error("→ Packet too short: totalBytes={} < HEADER_SIZE + packetLength={} ({} + {})",
                        data.length, TeltonikaConstants.HEADER_SIZE + packetLength, TeltonikaConstants.HEADER_SIZE, packetLength);
                throw new ProtocolException("Invalid data length: packet reports " + packetLength + " bytes, but actual remaining is " + (data.length - TeltonikaConstants.HEADER_SIZE));
            }
            final int avlDataLimit = buffer.position() + packetLength - 4;
            if (avlDataLimit < buffer.position() || avlDataLimit > buffer.limit()) {
                throw new ProtocolException("Calculated AVL data limit is invalid: " + avlDataLimit);
            }
            buffer.limit(avlDataLimit);

            if (buffer.remaining() < 2) {
                throw new ProtocolException("Not enough bytes for Codec ID and Record Count.");
            }
            final int codecId = buffer.get() & 0xFF;
            final int recordCount = buffer.get() & 0xFF;
            logger.info("→ codecId={}, recordCount={}", codecId, recordCount);

            final String version = TeltonikaConstants.CODECS.getOrDefault(codecId, "UNKNOWN");
            message.setProtocolVersion(version);
            message.setMessageType("DATA");

            final DeviceMessage resultMessage;
            switch (codecId) {
                case CODEC_8:
                case CODEC_8_EXT:
                    resultMessage = processCodec8Packet(buffer, message, recordCount, device);
                    break;
                case CODEC_16:
                    resultMessage = processCodec16Packet(buffer, message, recordCount, device);
                    break;
                default:
                    logger.error("→ Unsupported codec: {}", codecId);
                    throw new ProtocolException("Unsupported codec: " + codecId);
            }

            buffer.limit(buffer.capacity());
            buffer.position(TeltonikaConstants.HEADER_SIZE + packetLength - 4);
            if (buffer.remaining() >= 4) {
                final int crc = buffer.getInt();
                logger.info("→ Consumed CRC: 0x{}", Integer.toHexString(crc));
            } else {
                logger.warn("→ Missing CRC at the end of the packet. Remaining bytes: {}", buffer.remaining());
                if (validationMode == ValidationMode.STRICT) {
                    throw new ProtocolException("Missing CRC at the end of the data packet.");
                }
            }
            buffer.position(TeltonikaConstants.HEADER_SIZE + packetLength);

            return resultMessage;

        } catch (Exception e) {
            logger.error("→ Error handling Teltonika data packet", e);
            message.setMessageType("ERROR");
            message.addParsedData("error", e.getMessage());

            if (validationMode == ValidationMode.LENIENT) {
                throw new ProtocolException("Failed to handle data packet", e);
            } else {
                logger.warn("→ Packet parsing failed in non-STRICT mode. Returning partially processed message if available. Error: {}", e.getMessage());
                return message;
            }
        }
    }


    private DeviceMessage processCodec8Packet(
            final ByteBuffer buffer,
            final DeviceMessage message,
            final int recordCount,
            final Device device) throws ProtocolException {

        logger.info("→ Entered processCodec8Packet; buffer.position={}, remainingBytes={}",
                buffer.position(), buffer.remaining());

        final List<Position> positions = new ArrayList<>();
        int successfulRecords = 0;

        for (int i = 0; i < recordCount; i++) {
            final int recordStartPosition = buffer.position();
            logger.info("→ Parsing record #{}/{} starting at buffer position {}", i + 1, recordCount, recordStartPosition);

            if (buffer.remaining() < 25) {
                logger.warn("→ Not enough bytes for fixed part + Event ID of record #{} (remaining={}). Skipping remaining records.", i + 1, buffer.remaining());
                break;
            }
            try {
                final Position pos = parseCodec8Data(buffer);
                logger.info("→ Parsed fixed part of record #{}: ts={}, lat={}, lon={}",
                        i + 1, pos.getTimestamp(), pos.getLatitude(), pos.getLongitude());

                skipIoElements(buffer, CODEC_8);
                logger.info("→ Skipped I/O elements for record #{}; new buffer position={}", i + 1, buffer.position());

                pos.setDevice(device);
                pos.setProtocol("TELTONIKA");

                positionRepository.save(pos);
                logger.info("→ Saved position record #{} to database for device IMEI: {}", i + 1, device.getImei());

                positions.add(pos);
                successfulRecords++;

            } catch (final ProtocolException ex) {
                logger.warn("→ Failed to parse record #{} due to malformed data: {}", i + 1, ex.getMessage());
                if (validationMode == ValidationMode.STRICT) {
                    throw ex;
                } else {
                    logger.warn("Aborting further record parsing due to unrecoverable error in record #{}. Consuming remaining bytes in current AVL data block.", i + 1);
                    buffer.position(buffer.limit());
                    break;
                }
            }
        }

        message.addParsedData("positions", positions);
        if (!positions.isEmpty()) {
            message.setTimestamp(positions.get(positions.size() - 1).getTimestamp());
            Device latestDevice = deviceRepository.findByImei(device.getImei()).orElse(device);
            latestDevice.setLastLatitude(positions.get(positions.size() - 1).getLatitude());
            latestDevice.setLastLongitude(positions.get(positions.size() - 1).getLongitude());
            latestDevice.setLastSpeed(positions.get(positions.size() - 1).getSpeed());
            latestDevice.setLastPositionTime(positions.get(positions.size() - 1).getTimestamp());
            deviceRepository.save(latestDevice);
        }

        final ByteBuffer ack = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        ack.putInt(recordCount);
        message.addParsedData("response", ack.array());
        logger.info("→ processCodec8Packet: generated ACK for {} records", recordCount);

        return message;
    }

    private Position parseCodec8Data(final ByteBuffer buffer) throws ProtocolException {
        final Position position = new Position();

        if (buffer.remaining() < 25) {
            throw new ProtocolException("Not enough bytes for fixed AVL data and Event ID. Remaining: " + buffer.remaining());
        }

        final long ts = buffer.getLong();
        position.setTimestamp(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.of("UTC"))
        );

        final int priority = buffer.get() & 0xFF;
        logger.debug("→ parseCodec8Data: priority={}", priority);

        final int lonRaw = buffer.getInt();
        final int latRaw = buffer.getInt();
        final double longitude = lonRaw / 1e7;
        final double latitude = latRaw / 1e7;
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        logger.info("→ parseCodec8Data: lat={}, lon={}", latitude, longitude);

        position.setAltitude(buffer.getShort());

        position.setCourse((double) (buffer.getShort() & 0xFFFF));

        final int sats = buffer.get() & 0xFF;
        position.setValid(sats > 0);

        final double speedKnots = buffer.getShort() & 0xFFFF;
        position.setSpeed(speedKnots * 1.852);

        final int eventId = buffer.get() & 0xFF;
        logger.debug("→ parseCodec8Data: skipped Event ID={}", eventId);

        return position;
    }


    private DeviceMessage processCodec16Packet(final ByteBuffer buffer, final DeviceMessage message, final int recordCount, final Device device) throws ProtocolException {
        logger.info("→ Entered processCodec16Packet; buffer.position={}, remainingBytes={}",
                buffer.position(), buffer.remaining());

        final List<Position> positions = new ArrayList<>();
        int successfulRecords = 0;

        for (int i = 0; i < recordCount; i++) {
            final int recordStartPosition = buffer.position();
            logger.info("→ Parsing Codec16 record #{}/{} starting at buffer position {}", i + 1, recordCount, recordStartPosition);

            if (buffer.remaining() < 25) {
                logger.warn("→ Not enough bytes for fixed part + Event ID of Codec16 record #{} (remaining={}). Skipping remaining records.", i + 1, buffer.remaining());
                break;
            }

            try {
                final Position position = parseCodec8Data(buffer);
                logger.info("→ Parsed fixed part of Codec16 record #{}: ts={}, lat={}, lon={}",
                        i + 1, position.getTimestamp(), position.getLatitude(), position.getLongitude());

                skipIoElements(buffer, CODEC_16);
                logger.info("→ Skipped I/O elements for Codec16 record #{}; new buffer position={}", i + 1, buffer.position());

                position.setDevice(device);
                position.setProtocol("TELTONIKA");

                positionRepository.save(position);
                logger.info("→ Saved position record #{} to database for device IMEI: {}", i + 1, device.getImei());

                positions.add(position);
                successfulRecords++;
            } catch (final ProtocolException e) {
                logger.warn("Failed to parse Codec16 record #{} due to malformed data: {}", i + 1, e.getMessage());
                if (validationMode == ValidationMode.STRICT) {
                    throw e;
                } else {
                    logger.warn("Aborting further record parsing due to unrecoverable error in Codec16 record #{}. Consuming remaining bytes in current AVL data block.", i + 1);
                    buffer.position(buffer.limit());
                    break;
                }
            }
        }
        message.addParsedData("positions", positions);

        if (!positions.isEmpty()) {
            message.setTimestamp(positions.get(positions.size() - 1).getTimestamp());
            Device latestDevice = deviceRepository.findByImei(device.getImei()).orElse(device);
            latestDevice.setLastLatitude(positions.get(positions.size() - 1).getLatitude());
            latestDevice.setLastLongitude(positions.get(positions.size() - 1).getLongitude());
            latestDevice.setLastSpeed(positions.get(positions.size() - 1).getSpeed());
            latestDevice.setLastPositionTime(positions.get(positions.size() - 1).getTimestamp());
            deviceRepository.save(latestDevice);
        }

        final ByteBuffer response = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        response.putInt(successfulRecords);
        message.addParsedData("response", response.array());

        return message;
    }

    private Position parseCodec16Data(final ByteBuffer buffer) throws ProtocolException {
        final Position position = new Position();

        if (buffer.remaining() < 25) {
            throw new ProtocolException("Not enough bytes for fixed AVL data and Event ID in Codec16. Remaining: " + buffer.remaining());
        }

        final long ts = buffer.getLong();
        position.setTimestamp(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.of("UTC"))
        );

        final int priority = buffer.get() & 0xFF;
        logger.debug("→ parseCodec16Data: priority={}", priority);

        final int lonRaw = buffer.getInt();
        final int latRaw = buffer.getInt();
        final double longitude = lonRaw / 1e7;
        final double latitude = latRaw / 1e7;
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        logger.info("→ parseCodec16Data: lat={}, lon={}", latitude, longitude);

        position.setAltitude(buffer.getShort());

        position.setCourse((double) (buffer.getShort() & 0xFFFF));

        final int sats = buffer.get() & 0xFF;
        position.setValid(sats > 0);

        final double speedKnots = buffer.getShort() & 0xFFFF;
        position.setSpeed(speedKnots * 1.852);

        final int eventId = buffer.get() & 0xFF;
        logger.debug("→ parseCodec16Data: skipped Event ID={}", eventId);

        return position;
    }


    private DeviceMessage handleHeartbeat() {
        final DeviceMessage message = new DeviceMessage();
        message.setProtocol("TELTONIKA");
        message.setMessageType("HEARTBEAT");
        message.addParsedData("response", HEARTBEAT_RESPONSE);
        logger.info("Responded to heartbeat");
        return message;
    }

    private boolean isHeartbeatPacket(final byte[] data) {
        if (data == null) return false;

        if (data.length == 4) {
            return data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 0;
        }

        if (data.length == 8) {
            final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            return buffer.getInt() == 0 && buffer.getInt() == 0;
        }

        return false;
    }

    private boolean isImeiPacket(final byte[] data) {
        if (data == null || data.length < 2) {
            return false;
        }
        final int length = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        return length == IMEI_LENGTH && data.length == (2 + IMEI_LENGTH);
    }

    private void skipIoElements(final ByteBuffer buffer, final int codecId) throws ProtocolException {
        final int beforeAll = buffer.position();

        if (buffer.remaining() < 1) {
            logger.warn("→ Missing count byte for 1-byte I/O group. Remaining: {}. Exiting I/O skipping.", buffer.remaining());
            return;
        }
        final int count1Byte = buffer.get() & 0xFF;
        logger.debug("→ skipIoElements: 1-byte I/O count={}", count1Byte);
        if (buffer.remaining() < count1Byte) {
            throw new ProtocolException("Not enough bytes for 1-byte I/O elements. Expected " + count1Byte + ", remaining " + buffer.remaining());
        }
        buffer.position(buffer.position() + count1Byte);

        if (buffer.remaining() < 1) {
            logger.warn("→ Missing count byte for 2-byte I/O group. Remaining: {}. Exiting I/O skipping.", buffer.remaining());
            return;
        }
        final int count2Byte = buffer.get() & 0xFF;
        logger.debug("→ skipIoElements: 2-byte I/O count={}", count2Byte);
        if (buffer.remaining() < count2Byte * 2) {
            throw new ProtocolException("Not enough bytes for 2-byte I/O elements. Expected " + (count2Byte * 2) + ", remaining " + buffer.remaining());
        }
        buffer.position(buffer.position() + count2Byte * 2);

        if (buffer.remaining() < 1) {
            logger.warn("→ Missing count byte for 4-byte I/O group. Remaining: {}. Exiting I/O skipping.", buffer.remaining());
            return;
        }
        final int count4Byte = buffer.get() & 0xFF;
        logger.debug("→ skipIoElements: 4-byte I/O count={}", count4Byte);
        if (buffer.remaining() < count4Byte * 4) {
            throw new ProtocolException("Not enough bytes for 4-byte I/O elements. Expected " + (count4Byte * 4) + ", remaining " + buffer.remaining());
        }
        buffer.position(buffer.position() + count4Byte * 4);

        if (codecId == CODEC_8_EXT || codecId == CODEC_16) {
            if (buffer.remaining() < 1) {
                logger.warn("→ Missing count byte for 8-byte I/O group. Remaining: {}. Exiting I/O skipping.", buffer.remaining());
                return;
            }
            final int count8Byte = buffer.get() & 0xFF;
            logger.debug("→ skipIoElements: 8-byte I/O count={}", count8Byte);
            if (buffer.remaining() < count8Byte * 8) {
                throw new ProtocolException("Not enough bytes for 8-byte I/O elements. Expected " + (count8Byte * 8) + ", remaining " + buffer.remaining());
            }
            buffer.position(buffer.position() + count8Byte * 8);
        }
        logger.debug("→ skipIoElements: skipped from {} to {}", beforeAll, buffer.position());
    }

    private boolean isSupportedCodec(final int codecId) {
        return TeltonikaConstants.CODECS.containsKey(codecId);
    }

    private String cleanImei(final String imei) {
        return imei.replaceAll("[^0-9]", "");
    }

    private boolean isValidImei(final String imei) {
        return imei != null && IMEI_PATTERN.matcher(imei).matches() && imei.length() == IMEI_LENGTH;
    }

    //@Override
    public byte[] generateResponse(final int recordCount) {
        final ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(recordCount);
        return buffer.array();
    }

    @Override
    public byte[] generateResponse(final Position position) {
        return generateResponse(1);
    }

    @Override
    public boolean supports(final String protocolType) {
        return "TELTONIKA".equalsIgnoreCase(protocolType);
    }

    @Override
    public boolean canHandle(final String protocol, final String version) {
        return "TELTONIKA".equalsIgnoreCase(protocol) &&
                (version == null || version.startsWith("CODEC") || "1.0".equalsIgnoreCase(version));
    }


    public enum ValidationMode {
        STRICT, LENIENT, RECOVER
    }

    public static class TeltonikaConstants {
        public static final int HEADER_SIZE = 8;
        public static final Map<Integer, String> CODECS = Map.of(
                0x08, "CODEC8",
                0x8E, "CODEC8_EXT",
                0x10, "CODEC16"
        );
    }
}