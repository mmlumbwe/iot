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
import java.nio.charset.StandardCharsets;

import com.assettrack.iot.config.UnitsConverter;

@Protocol(value = "ASTRA_AT240", version = "1.0")
@Component
public class AstraAt240Handler implements ProtocolHandler {

    private static final Logger logger = LoggerFactory.getLogger(AstraAt240Handler.class);
    private static final byte PROTOCOL_X = (byte) 'X';

    // Helper method to check if a specific bit is set in a long value
    private static boolean checkBit(long value, int bit) {
        return ((value >> bit) & 1) > 0;
    }

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

            // Read 15-byte ASCII IMEI
            String imei = buf.readCharSequence(15, StandardCharsets.US_ASCII).toString();

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

            // Read 15-byte ASCII IMEI
            String imei = buf.readCharSequence(15, StandardCharsets.US_ASCII).toString();
            //message.setDeviceId(imei); // Set IMEI on the DeviceMessage
            message.setImei(imei);

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
                    // Add other attributes if set in Position object
                    if (p.getBatteryLevel() != null) {
                        rec.put("batteryLevel", p.getBatteryLevel());
                    }
                    if (p.getIgnition() != null) {
                        rec.put("ignition", p.getIgnition());
                    }
                    if (p.getSatellites() != null) {
                        rec.put("satellites", p.getSatellites());
                    }
                    // You might want to add event and status here as well if they are important
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

        buf.readUnsignedByte(); // Consume the 1-byte event index
        buf.readUnsignedByte(); // Consume the 1-byte command

        long mask = buf.readUnsignedInt(); // Main mask (4 bytes)
        long extendedMask = 0;
        // Check if extended mask is present (bit 31 of main mask)
        if (checkBit(mask, 31)) {
            extendedMask = buf.readUnsignedInt(); // Extended mask (4 bytes)
        }

        // Device time is always present (6 bytes: YYMMDDhhmmss)
        LocalDateTime deviceTime = readDateTime(buf, "device");
        position.setTimestamp(deviceTime); // Initial timestamp setting

        // Event (2 bytes) and Status (2 bytes) are always present
        int event = buf.readUnsignedShort(); // Corrected from readUnsignedInt()
        int status = buf.readUnsignedShort();
        // You can add these as attributes to your Position model if needed:
        // position.set("event", event);
        // position.set("status", status);

        // GPS Fix is indicated by bit 1 of the main mask
        boolean hasFix = checkBit(mask, 1);
        position.setValid(hasFix);

        if (hasFix) {
            // Fix time (6 bytes: YYMMDDhhmmss) - present only if hasFix
            LocalDateTime fixTime = readDateTime(buf, "fix");
            position.setTimestamp(fixTime); // Update timestamp to fixTime if a valid fix exists

            // Latitude (4 bytes)
            position.setLatitude(buf.readInt() * 0.000001); // Using 0.000001 for precision

            // Longitude (4 bytes)
            position.setLongitude(buf.readInt() * 0.000001); // Using 0.000001 for precision

            // Speed (1 byte) - Traccar reads directly, then converts
            position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedByte()));

            buf.readUnsignedByte(); // Max speed since last report (1 byte) - consume this byte

            // Course (1 byte) - Traccar reads directly
            position.setCourse((double) buf.readUnsignedByte());

            // Altitude (1 byte) - Corrected scaling/offset based on Traccar
            position.setAltitude((short) (buf.readUnsignedByte() * 10 - 1000));

            // Odometer trip (2 bytes)
            // Traccar reads this as 0.1 * value. If Position has Key_Odometer_Trip, set it.
            // For now, just consume the bytes if not storing directly.
            buf.readUnsignedShort();
        }

        // Process other masks based on Traccar's AstraProtocolDecoder bit positions.
        // Bit 4: Power/Battery Level (main mask)
        if (checkBit(mask, 4)) {
            buf.readUnsignedByte(); // Power (consume, or set as attribute if needed)
            position.setBatteryLevel((double) buf.readUnsignedByte()); // Battery Level
        }

        // Bit 5: States (main mask)
        if (checkBit(mask, 5)) {
            buf.readUnsignedShort(); // states (2 bytes)
            buf.readUnsignedShort(); // changes mask (2 bytes)
        }
        // Bit 6: ADC values (main mask)
        if (checkBit(mask, 6)) {
            buf.readUnsignedShort(); // adc1 (2 bytes)
            buf.readUnsignedShort(); // adc2 (2 bytes)
        }

        // Example for reading other mask bits from the main mask (bits 7-30) and extended mask (bits 0-31)
        // You would continue adding checks for relevant bits as needed, consuming the correct number of bytes.
        // For example:
        if (checkBit(mask, 7)) { // Bit 7: Device Temperature
            buf.readUnsignedByte(); // deviceTemp (1 byte)
        }
        if (checkBit(mask, 8)) { // Bit 8: Temperature 1
            buf.readUnsignedByte(); // temp1 (1 byte)
        }
        // ... and so on for other bits in 'mask' and 'extendedMask'

        return position;
    }

    @Override
    public byte[] generateResponse(Position position) {
        // As requested, the acknowledgement (0x06) is disabled.
        // Return null for no response.
        return null;
    }
}