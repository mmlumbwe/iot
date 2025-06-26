package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.assettrack.iot.config.UnitsConverter;

@Protocol(value = "ASTRA_AT240", version = "1.0")
@Component
public class AstraAt240Handler implements ProtocolHandler {

    private static final Logger logger = LoggerFactory.getLogger(AstraAt240Handler.class);
    private static final byte PROTOCOL_X = (byte) 'X';

    @Override
    public boolean supports(String protocolType) {
        return "ASTRA_AT240".equalsIgnoreCase(protocolType);
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        return supports(protocol);
    }

    private LocalDateTime safeReadDateTime(ByteBuf buf, String context) {
        int yearRaw = buf.readUnsignedByte();
        int month = buf.readUnsignedByte();
        int day = buf.readUnsignedByte();
        int hour = buf.readUnsignedByte();
        int minute = buf.readUnsignedByte();
        int second = buf.readUnsignedByte();
        int year = 2000 + yearRaw;
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second);
        } catch (DateTimeException e) {
            logger.warn("Invalid {} timestamp {}/{}/{} {}:{}:{}, using now",
                    context, year, month, day, hour, minute, second);
            return LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    @Override
    public Position parsePosition(byte[] rawMessage) throws ProtocolException {
        if (rawMessage == null || rawMessage.length < 4) {
            throw new ProtocolException("AT240 message too short for parsing position");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(rawMessage);
        try {
            byte type = buf.readByte();           // protocol flag
            buf.readUnsignedShort();              // length
            if (type != PROTOCOL_X) {
                throw new ProtocolException("Only X protocol supported for single-position parsing");
            }
            int count = buf.readUnsignedByte();   // record count
            buf.skipBytes(7);                     // skip IMEI (4 + 3 bytes)
            Position result = null;
            for (int i = 0; i < count; i++) {
                Position p = decodeRecord(buf);
                if (result == null && p != null) {
                    result = p;
                }
            }
            return result;
        } finally {
            buf.release();
        }
    }

    @Override
    public DeviceMessage handle(byte[] data, ChannelHandlerContext ctx) throws ProtocolException {
        logger.info("Processing ASTRA_AT240 packet, length={} bytes", data.length);
        if (data.length < 4) {
            throw new ProtocolException("Invalid AT240 packet: too short.");
        }
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("ASTRA_AT240");
        message.setMessageType("DATA");
        Map<String, Object> parsed = new HashMap<>();
        message.setParsedData(parsed);

        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            byte type = buf.readByte();
            buf.readUnsignedShort();
            if (ctx != null) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x06}));
            }
            if (type != PROTOCOL_X) {
                throw new ProtocolException(String.format(
                        "Unknown Astra protocol type: 0x%02X", type
                ));
            }
            int count = buf.readUnsignedByte();
            buf.skipBytes(7); // skip IMEI
            List<Map<String, Object>> records = new ArrayList<>();
            Position primary = null;
            for (int i = 0; i < count; i++) {
                Position p = decodeRecord(buf);
                if (p != null) {
                    Map<String, Object> rec = new HashMap<>();
                    rec.put("timestamp", p.getTimestamp());
                    rec.put("latitude", p.getLatitude());
                    rec.put("longitude", p.getLongitude());
                    rec.put("speed", p.getSpeed());
                    rec.put("course", p.getCourse());
                    rec.put("altitude", p.getAltitude());
                    rec.put("valid", p.getValid());
                    records.add(rec);
                    logger.info("AstraAt240Handler: Parsed Record {}: {}", i + 1, rec);
                    if (primary == null) {
                        primary = p;
                    }
                }
            }
            parsed.put("records", records);
            if (primary != null) {
                message.setPosition(primary);
                logger.info("AstraAt240Handler: Setting primary position from the first parsed record.");
            }
        } finally {
            buf.release();
        }
        logger.info("AstraAt240Handler: Finished parsing ASTRA_AT240 packet. Total records={}"
                , ((List<?>) message.getParsedData().get("records")).size());
        return message;
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        return handle(data, null);
    }

    private Position decodeRecord(ByteBuf buf) {
        long mask = buf.readUnsignedInt();
        buf.readUnsignedByte(); // index slot

        // Device timestamp
        LocalDateTime deviceTime = safeReadDateTime(buf, "device");
        boolean hasFix = (mask & 2L) != 0;

        Position position = new Position();
        position.setProtocol("ASTRA_AT240");
        position.setValid(hasFix);

        if (!hasFix) {
            buf.skipBytes(1 + 3); // skip event + status
            position.setTimestamp(deviceTime);
            return position;
        }

        // Event and status (skip since not stored)
        buf.readUnsignedByte();
        buf.readUnsignedMedium();

        // Fix timestamp
        LocalDateTime fixTime = safeReadDateTime(buf, "fix");
        position.setTimestamp(fixTime);

        // Coordinates & movement
        position.setLatitude(buf.readInt() * 1e-6);
        position.setLongitude(buf.readInt() * 1e-6);
        double speedKph = buf.readUnsignedByte() * 2;
        position.setSpeed(UnitsConverter.knotsFromKph(speedKph));
        buf.readUnsignedByte(); // reserved/max speed
        position.setCourse((double) (buf.readUnsignedByte() * 2));
        position.setAltitude((short) (buf.readUnsignedByte() * 20));
        buf.readUnsignedShort();  // odometer trip (ignored)

        return position;
    }

    @Override
    public byte[] generateResponse(Position position) {
        return new byte[]{0x06};
    }
}
