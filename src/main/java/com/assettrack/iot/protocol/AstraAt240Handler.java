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
import java.nio.charset.StandardCharsets; // Added for IMEI reading

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

    /**
     * Helper method to read a date/time stamp in the format YYMMDDhhmmss (6 bytes).
     * This is consistent with Traccar's AstraProtocolDecoder.
     */
    private LocalDateTime readDateTime(ByteBuf buf, String context) {
        int yearRaw = buf.readUnsignedByte();
        int month = buf.readUnsignedByte();
        int day = buf.readUnsignedByte();
        int hour = buf.readUnsignedByte();
        int minute = buf.readUnsignedByte();
        int second = buf.readUnsignedByte();
        // Assuming 20xx year for raw values like '14' (2014)
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
            byte type = buf.readByte();           // protocol flag (1 byte)
            buf.readUnsignedShort();              // total packet length (2 bytes)
            if (type != PROTOCOL_X) {
                throw new ProtocolException("Only X protocol supported for single-position parsing");
            }
            int count = buf.readUnsignedByte();   // record count (1 byte)

            // Corrected: Read 15-byte ASCII IMEI as per Traccar's AstraProtocolDecoder
            String imei = buf.readCharSequence(15, StandardCharsets.US_ASCII).toString();
            // You can optionally store this IMEI in your Position or DeviceMessage object if needed

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
            byte type = buf.readByte();          // protocol flag (1 byte)
            buf.readUnsignedShort();             // total packet length (2 bytes)

            // As requested, acknowledgement is disabled.
            // To re-enable, uncomment the line below:
            // if (ctx != null) {
            //     ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x06}));
            // }

            if (type != PROTOCOL_X) {
                throw new ProtocolException(String.format("Unknown Astra protocol type: 0x%02X", type));
            }

            int count = buf.readUnsignedByte();  // record count (1 byte)

            // Corrected: Read 15-byte ASCII IMEI as per Traccar's AstraProtocolDecoder
            String imei = buf.readCharSequence(15, StandardCharsets.US_ASCII).toString();
            //message.setDeviceId(imei); // Set IMEI on the DeviceMessage

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

    private Position decodeRecord(ByteBuf buf) {
        Position position = new Position();
        position.setProtocol("ASTRA_AT240");

        buf.readUnsignedByte(); // Consume the 1-byte index slot
        buf.readUnsignedByte(); // Consume the 1-byte command (as per Traccar's AstraProtocolDecoder)

        // Corrected mask reading: 4 bytes (as per Traccar's AstraProtocolDecoder)
        long mask = buf.readUnsignedInt();

        // Device time is always present (6 bytes: YYMMDDhhmmss)
        LocalDateTime deviceTime = readDateTime(buf, "device");
        position.setTimestamp(deviceTime); // Initial timestamp setting

        // Event (4 bytes) and Status (2 bytes) are always present
        long event = buf.readUnsignedInt();
        int status = buf.readUnsignedShort();
        // You can add these as attributes to your Position model if needed:
        // position.set("event", event);
        // position.set("status", status);

        // Corrected: GPS Fix is indicated by bit 0 of the mask (mask & 1L)
        boolean hasFix = (mask & 1L) > 0;
        position.setValid(hasFix);

        if (hasFix) {
            // Fix time (6 bytes: YYMMDDhhmmss) - present only if hasFix
            LocalDateTime fixTime = readDateTime(buf, "fix");
            position.setTimestamp(fixTime); // Update timestamp to fixTime if a valid fix exists

            // Latitude (4 bytes)
            position.setLatitude(buf.readInt() * 1e-6);
            // Longitude (4 bytes)
            position.setLongitude(buf.readInt() * 1e-6);

            // Speed (1 byte)
            double speedKph = buf.readUnsignedByte() * 2;
            position.setSpeed(UnitsConverter.knotsFromKph(speedKph));

            buf.readUnsignedByte(); // Max speed since last report (1 byte) - consume this byte

            // Course (1 byte)
            position.setCourse((double) (buf.readUnsignedByte() * 2));

            // Altitude (1 byte)
            position.setAltitude((short) (buf.readUnsignedByte() * 20));

            buf.readUnsignedShort(); // Odometer trip (2 bytes) - consume this byte
        } else {
            // If no fix, skip the bytes that would have been read in the hasFix block to maintain alignment.
            // Total bytes to skip:
            // fixTime (6 bytes) + latitude (4 bytes) + longitude (4 bytes) + speed (1 byte) +
            // max speed (1 byte) + course (1 byte) + altitude (1 byte) + odometer trip (2 bytes) = 20 bytes
            buf.skipBytes(20);
        }

        // Process other masks. These generally correspond to specific bit positions in the mask.
        // For example, based on Traccar's AstraProtocolDecoder:
        if ((mask & 2L) > 0) { // Bit 1: Power/Battery Level (2 bytes total)
            buf.readUnsignedByte(); // Power - consume this byte
            position.setBatteryLevel((double) buf.readUnsignedByte()); // Battery Level
        }

        if ((mask & 4L) > 0) { // Bit 2: States (4 bytes total)
            buf.readUnsignedShort(); // states (2 bytes)
            buf.readUnsignedShort(); // changes mask (2 bytes)
        }
        if ((mask & 8L) > 0) { // Bit 3: ADC values (4 bytes total)
            buf.readUnsignedShort(); // adc1 (2 bytes)
            buf.readUnsignedShort(); // adc2 (2 bytes)
        }
        // Add more 'if (mask & XXXL) > 0' blocks here for other data fields indicated by the mask,
        // ensuring the correct number of bytes are read/skipped for each.

        return position;
    }

    @Override
    public byte[] generateResponse(Position position) {
        // As requested, the acknowledgement (0x06) is disabled.
        // Return null for no response.
        return null;
    }
}