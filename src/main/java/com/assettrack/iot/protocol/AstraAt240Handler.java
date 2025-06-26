package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.config.UnitsConverter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private String readImei(ByteBuf buf) {
        return String.format("%08d", buf.readUnsignedInt())
                + String.format("%07d", buf.readUnsignedMedium());
    }

    private LocalDateTime readDateTime(ByteBuf buf) {
        int year   = buf.readUnsignedByte() + 2000;
        int month  = buf.readUnsignedByte();
        int day    = buf.readUnsignedByte();
        int hour   = buf.readUnsignedByte();
        int minute = buf.readUnsignedByte();
        int second = buf.readUnsignedByte();
        return LocalDateTime.of(year, month, day, hour, minute, second);
    }

    @Override
    public Position parsePosition(final byte[] rawMessage) throws ProtocolException {
        if (rawMessage == null || rawMessage.length < 4) {
            throw new ProtocolException("AT240 message too short for parsing position");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(rawMessage);
        try {
            byte protocolType = buf.readByte();
            buf.readUnsignedShort(); // skip length
            if (protocolType != PROTOCOL_X) {
                throw new ProtocolException("Only X protocol supported for single-position parsing");
            }
            int count = buf.readUnsignedByte();
            String imei = readImei(buf);
            Position result = null;
            for (int i = 0; i < count; i++) {
                Position p = decodeRecord(buf);
                if (result == null) {
                    result = p;
                }
            }
            return result;
        } finally {
            buf.release();
        }
    }

    @Override
    public DeviceMessage handle(final byte[] data, final ChannelHandlerContext ctx) throws ProtocolException {
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
            byte protocolType = buf.readByte();
            buf.readUnsignedShort(); // skip length
            if (ctx != null) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x06}));
            }
            if (protocolType != PROTOCOL_X) {
                throw new ProtocolException(String.format(
                        "Unknown Astra protocol type: 0x%02X", protocolType
                ));
            }

            int count = buf.readUnsignedByte();
            String imei = readImei(buf);
            message.setImei(imei);

            List<Map<String, Object>> records = new ArrayList<>();
            Position primary = null;
            for (int i = 0; i < count; i++) {
                Position p = decodeRecord(buf);
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
    public DeviceMessage handle(final byte[] data) throws ProtocolException {
        return handle(data, null);
    }

    private Position decodeRecord(ByteBuf buf) {
        long mask = buf.readUnsignedInt();
        buf.readUnsignedByte(); // index

        LocalDateTime deviceTime = readDateTime(buf);
        buf.readUnsignedByte(); // event code
        buf.readUnsignedMedium(); // status

        Position position = new Position();
        position.setProtocol("ASTRA_AT240");
        boolean hasFix = (mask & 2L) != 0;
        position.setValid(hasFix);

        LocalDateTime recordTime = deviceTime;
        if (hasFix) {
            LocalDateTime fixTime = readDateTime(buf);
            recordTime = fixTime;

            position.setLatitude(buf.readInt() * 1e-6);
            position.setLongitude(buf.readInt() * 1e-6);
            double speedKph = buf.readUnsignedByte() * 2;
            position.setSpeed(UnitsConverter.knotsFromKph(speedKph));
            buf.readUnsignedByte(); // max speed
            position.setCourse((double) (buf.readUnsignedByte() * 2));
            position.setAltitude((short) (buf.readUnsignedByte() * 20));
            buf.readUnsignedShort(); // odometer (ignored)
        }
        position.setTimestamp(recordTime);
        return position;
    }

    @Override
    public byte[] generateResponse(Position position) {
        return new byte[]{0x06};
    }
}
