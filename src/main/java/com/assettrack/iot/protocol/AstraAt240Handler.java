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
            logger.warn("Invalid {} timestamp {}/{}/{} {}:{}:{}, using now", context, year, month, day, hour, minute, second);
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
        int totalRecords = 0;
        try {
            byte type = buf.readByte();
            buf.readUnsignedShort();
            if (ctx != null) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x06}));
            }
            if (type != PROTOCOL_X) {
                throw new ProtocolException(String.format("Unknown Astra protocol type: 0x%02X", type));
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
                    totalRecords++;
                    logger.info("AstraAt240Handler: Parsed Record {}: {}", totalRecords, rec);
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
        logger.info("AstraAt240Handler: Finished parsing ASTRA_AT240 packet. Total records={}", totalRecords);
        return message;
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        return handle(data, null);
    }

    private LocalDateTime readAstraTime(ByteBuf buf) {
        long secondsSinceEpoch = buf.readUnsignedInt();
        // Astra epoch is 1980-01-06 00:00:00 UTC
        // Convert seconds to milliseconds
        long millisSinceEpoch = secondsSinceEpoch * 1000L;
        // Calculate milliseconds from Java epoch (1970-01-01 00:00:00 UTC)
        // Difference between 1980-01-06 and 1970-01-01 is 315964800 seconds
        // (315964800 * 1000L milliseconds)
        long javaEpochMillis = 315964800000L; // Milliseconds from 1970-01-01 to 1980-01-06
        return LocalDateTime.ofEpochSecond((millisSinceEpoch + javaEpochMillis) / 1000L, 0, ZoneOffset.UTC);
    }

    private Position decodeRecord(ByteBuf buf) {
        Position position = new Position();
        position.setProtocol("ASTRA_AT240");

        // Read index first
        buf.readUnsignedByte(); // index slot (consume the byte, but not strictly needed for Position object)

        // Correctly read the 6-byte mask
        long mask = ((long) buf.readUnsignedShort() << 32) + buf.readUnsignedInt();

        // Device time is always present
        LocalDateTime deviceTime = readAstraTime(buf); // Use the new helper method
        position.setTimestamp(deviceTime); // Set device time as primary timestamp

        // Event and Status are always present
        long event = buf.readUnsignedInt();
        int status = buf.readUnsignedShort();
        // You'll need to decide how to store these in your Position model if desired
        // position.set("event", event); // Example if you add generic attribute support
        // position.set("status", status);

        // Check for GPS Fix (mask & 2L)
        boolean hasFix = (mask & 2L) > 0;
        position.setValid(hasFix);

        if (hasFix) {
            LocalDateTime fixTime = readAstraTime(buf); // Use the new helper method
            position.setTimestamp(fixTime); // Update timestamp to fixTime if valid
            position.setLatitude(buf.readInt() * 1e-6);
            position.setLongitude(buf.readInt() * 1e-6);
            double speedKph = buf.readUnsignedByte() * 2;
            position.setSpeed(UnitsConverter.knotsFromKph(speedKph));
            buf.readUnsignedByte(); // max speed since last report
            position.setCourse((double) (buf.readUnsignedByte() * 2));
            position.setAltitude((short) (buf.readUnsignedByte() * 20)); // Cast to short for your Position model
            buf.readUnsignedShort(); // odometer trip (ignored for primary position)
        } else {
            // If no fix, Traccar often tries to use the last known location.
            // Your model doesn't explicitly support this, so you might just keep
            // latitude/longitude as null or use a default.
            // The key is to NOT read GPS data if hasFix is false to avoid misalignment.
        }

        // Process other masks as per AstraProtocolDecoder.java's decodeX method
        // Ensure you read the correct number of bytes for each mask bit set
        if ((mask & 1L) > 0) {
            position.setBatteryLevel(buf.readUnsignedByte() * 0.2); // Power
            // You might need to add power attribute to your Position.java or attributes JSON
            // position.set("power", buf.readUnsignedByte() * 0.2);
            position.setBatteryLevel((double) buf.readUnsignedByte()); // Battery Level
        }

        // ... continue with other masks (4L, 8L, 16L, etc.) in the same manner
        // ensuring proper byte reading and field assignment.
        // For example:
        if ((mask & 4L) > 0) {
            buf.readUnsignedShort(); // states
            buf.readUnsignedShort(); // changes mask
        }
        if ((mask & 8L) > 0) {
            buf.readUnsignedShort(); // adc1
            buf.readUnsignedShort(); // adc2
        }
        // etc.
        // The key is to ensure every byte for every set bit in the mask is read to keep alignment.
        // If your Position model doesn't store a specific attribute, you can just read and discard it (`buf.skipBytes()`)
        // or store it in your generic `attributes` JSON field.

        return position;
    }

    @Override
    public byte[] generateResponse(Position position) {
        return new byte[]{0x06};
    }
}
