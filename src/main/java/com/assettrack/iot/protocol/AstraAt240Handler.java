package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil; // Added for hexDump if needed for diagnostics
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.assettrack.iot.config.UnitsConverter; // Assuming this utility exists

@Protocol(value = "ASTRA_AT240", version = "1.0")
@Component
public class AstraAt240Handler implements ProtocolHandler {

    private static final Logger logger = LoggerFactory.getLogger(AstraAt240Handler.class);
    private static final byte PROTOCOL_X = (byte) 'X'; // Define the 'X' protocol byte
    // Note: The original AstraProtocolDecoder also defines MSG_HEARTBEAT and MSG_DATA,
    // but AstraAt240Handler currently only focuses on 'X' protocol for position parsing.

    @Override
    public boolean supports(String protocolType) {
        return "ASTRA_AT240".equalsIgnoreCase(protocolType);
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        return supports(protocol);
    }

    // Helper method to read IMEI, mirroring AstraProtocolDecoder's logic
    private String readImei(ByteBuf buf) {
        // Reads 4 bytes (UnsignedInt) and 3 bytes (UnsignedMedium)
        return String.format("%08d", buf.readUnsignedInt()) + String.format("%07d", buf.readUnsignedMedium());
    }

    // This method is similar to readTime in AstraProtocolDecoder, but uses LocalDateTime
    // Astra epoch is 1980-01-06 00:00:00 UTC
    private LocalDateTime readAstraTime(ByteBuf buf) {
        long secondsSinceEpoch = buf.readUnsignedInt();
        // Difference between 1980-01-06 and 1970-01-01 (Java epoch) is 315964800 seconds
        long javaEpochSecondsOffset = 315964800L;
        return LocalDateTime.ofEpochSecond(secondsSinceEpoch + javaEpochSecondsOffset, 0, ZoneOffset.UTC);
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
                // The handler is specifically designed for X protocol for single position.
                // If 'K' protocol messages are expected, you'd need a decodeK equivalent here.
                throw new ProtocolException("Only X protocol supported for single-position parsing");
            }
            int count = buf.readUnsignedByte();   // record count
            String imei = readImei(buf);          // Read IMEI (4 + 3 bytes)
            logger.debug("Parsing AT240 packet with IMEI: {}", imei);

            Position result = null;
            for (int i = 0; i < count; i++) {
                Position p = decodeRecord(buf); // Decode each record
                if (result == null && p != null) {
                    result = p; // Take the first valid position as the primary one
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
            byte type = buf.readByte(); // protocol flag
            buf.readUnsignedShort();    // length
            if (ctx != null) {
                // Send acknowledgment mirroring AstraProtocolDecoder
                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x06}));
            }
            if (type != PROTOCOL_X) {
                throw new ProtocolException(String.format("Unknown Astra protocol type: 0x%02X", type));
            }

            int count = buf.readUnsignedByte(); // record count
            String imei = readImei(buf);        // Read IMEI
            logger.debug("Handling AT240 packet with IMEI: {}, records: {}", imei, count);

            List<Map<String, Object>> records = new ArrayList<>();
            Position primary = null;
            for (int i = 0; i < count; i++) {
                Position p = decodeRecord(buf); // Decode each record using the enhanced method
                if (p != null) {
                    // Populate a map for generic parsed data logging
                    Map<String, Object> rec = new HashMap<>();
                    rec.put("timestamp", p.getTimestamp());
                    rec.put("latitude", p.getLatitude());
                    rec.put("longitude", p.getLongitude());
                    rec.put("speed", p.getSpeed());
                    rec.put("course", p.getCourse());
                    rec.put("altitude", p.getAltitude());
                    rec.put("valid", p.getValid());
                    rec.put("batteryLevel", p.getBatteryLevel()); // Add battery level to parsed data
                    // Add other attributes if mapped from the Position object or directly from mask parsing
                    // For example:
                    // rec.put("satellites", p.getSatellites());

                    records.add(rec);
                    totalRecords++;
                    logger.info("AstraAt240Handler: Parsed Record {}: {}", totalRecords, rec);
                    if (primary == null) {
                        primary = p; // Set the first valid position as primary
                    }
                }
            }
            parsed.put("imei", imei); // Include IMEI in parsed data
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

    /**
     * Decodes a single record from the Astra 'X' protocol.
     * This method now thoroughly reads all bytes according to the mask,
     * even if they are not directly mapped to the Position object, to ensure
     * proper buffer advancement for subsequent records.
     * @param buf The ByteBuf containing the record data.
     * @return A Position object populated with decoded data, or null if parsing fails.
     */
    private Position decodeRecord(ByteBuf buf) {
        Position position = new Position();
        position.setProtocol("ASTRA_AT240");

        buf.readUnsignedByte(); // index slot (consume this byte)

        // Correctly read the 6-byte mask (2 bytes for short, 4 bytes for int)
        long mask = ((long) buf.readUnsignedShort() << 32) + buf.readUnsignedInt();

        // Device time is always present
        LocalDateTime deviceTime = readAstraTime(buf); // Use the helper method
        position.setTimestamp(deviceTime); // Set device time as primary timestamp

        // Event and Status are always present
        long event = buf.readUnsignedInt();
        int status = buf.readUnsignedShort();
        // You might store these in a JSON attributes string if your Position model supports it
        // position.setAttributes(new Gson().toJson(Map.of("event", event, "status", status)));

        // --- Process mask-dependent fields, ensuring all bytes are consumed ---
        if ((mask & 1L) > 0) { // Power & Battery
            // Power is unsigned byte * 0.2
            // For simplicity, directly setting batteryLevel, or you could add a 'power' field to Position
            // position.set("power", buf.readUnsignedByte() * 0.2); // Example if you add custom attributes
            buf.readUnsignedByte(); // Consume power byte even if not used directly
            position.setBatteryLevel((double) buf.readUnsignedByte()); // Battery Level (unsigned byte)
        }

        boolean hasFix = (mask & 2L) > 0; // Location Data
        position.setValid(hasFix);
        if (hasFix) {
            LocalDateTime fixTime = readAstraTime(buf); // Fix time
            position.setTimestamp(fixTime); // Update timestamp to fixTime if valid
            position.setLatitude(buf.readInt() * 1e-6); // Latitude
            position.setLongitude(buf.readInt() * 1e-6); // Longitude
            double speedKph = buf.readUnsignedByte() * 2; // Speed in KPH
            position.setSpeed(UnitsConverter.knotsFromKph(speedKph)); // Convert to knots
            buf.readUnsignedByte(); // max speed since last report (consume)
            position.setCourse((double) (buf.readUnsignedByte() * 2)); // Course
            position.setAltitude((short) (buf.readUnsignedByte() * 20)); // Altitude
            buf.readUnsignedShort(); // odometer trip (consume)
        } else {
            // If no fix, Traccar's AstraProtocolDecoder often tries to get the last known location.
            // Your Position model doesn't explicitly support a 'last location' concept here.
            // Latitude, Longitude, Speed, Course, Altitude will remain null or their default values.
        }

        if ((mask & 4L) > 0) { // States & Changes
            buf.readUnsignedShort(); // states (consume)
            buf.readUnsignedShort(); // changes mask (consume)
        }

        if ((mask & 8L) > 0) { // ADC values
            buf.readUnsignedShort(); // adc1 (consume)
            buf.readUnsignedShort(); // adc2 (consume)
        }

        if ((mask & 16L) > 0) { // Accelerometer and Idle Hours
            buf.readByte(); // xMax (consume)
            buf.readByte(); // xMin (consume)
            buf.readByte(); // yMax (consume)
            buf.readByte(); // yMin (consume)
            buf.readByte(); // zMax (consume)
            buf.readByte(); // zMin (consume)
            buf.readUnsignedShort(); // idleHours (consume)
        }

        if ((mask & 32L) > 0) { // GPS & RSSI
            int value = buf.readUnsignedByte(); // consume
            position.setSatellites(value & 0xF); // Satellites (lower 4 bits)
            // RSSI (upper 4 bits) - if you add this to Position model
            // position.set("rssi", value >> 4);
        }

        if ((mask & 64L) > 0) { // MCC & MNC
            buf.readUnsignedShort(); // mcc (consume)
            buf.readUnsignedShort(); // mnc (consume)
        }

        if ((mask & 128L) > 0) { // Geofences
            buf.readUnsignedByte(); // geofences (consume)
        }

        if ((mask & 256L) > 0) { // Driver ID
            buf.readUnsignedByte(); // source (consume)
            buf.readLong(); // driver id (consume)
        }

        if ((mask & 512L) > 0) { // Trailer ID
            buf.readUnsignedByte(); // source (consume)
            buf.skipBytes(10); // trailer id (consume)
            buf.readUnsignedByte(); // status (consume)
        }

        if ((mask & 1024L) > 0) { // Axle Weight
            buf.readUnsignedShort(); // axleWeight (consume)
        }

        if ((mask & 2048L) > 0) { // Odometer & Hours
            // If Position model had odometer/hours fields:
            // position.setOdometer(buf.readUnsignedMedium() * 1000);
            // position.setHours(buf.readUnsignedShort() * 3_600_000);
            buf.readUnsignedMedium(); // Odometer (consume)
            buf.readUnsignedShort(); // Hours (consume)
        }

        if ((mask & 4096L) > 0) { // FMS/OBD Data 1
            buf.readUnsignedByte(); // wheelSpeedMax (consume)
            buf.readUnsignedByte(); // wheelSpeedAvg (consume)
            buf.readUnsignedByte(); // rpmMax (consume)
            buf.readUnsignedByte(); // rpmAvg (consume)
            buf.readUnsignedByte(); // acceleratorMax (consume)
            buf.readUnsignedByte(); // acceleratorAvg (consume)
            buf.readUnsignedByte(); // engineLoadMax (consume)
            buf.readUnsignedByte(); // engineLoadAvg (consume)
            buf.readUnsignedShort(); // odometerTrip (consume)
            buf.readByte(); // coolantTemp (consume)
            buf.readUnsignedShort(); // fmsStatus (consume)
            buf.readUnsignedShort(); // fmsEvents (consume)
            // If Position model had fuel level/used:
            // position.setFuelLevel(buf.readUnsignedByte());
            // position.setFuelUsed(buf.readUnsignedInt() * 0.5);
            buf.readUnsignedByte(); // fuelLevel (consume)
            buf.readUnsignedInt(); // fuelUsed (consume)
        }

        if ((mask & 8192L) > 0) { // FMS/OBD Data 2 (OBD-specific fields)
            buf.readUnsignedByte(); // wheelSpeedMax (consume)
            buf.readUnsignedByte(); // wheelSpeedAvg (consume)
            buf.readUnsignedByte(); // rpmMax (consume)
            buf.readUnsignedByte(); // rpmAvg (consume)
            buf.readUnsignedByte(); // acceleratorMax (consume)
            buf.readUnsignedByte(); // acceleratorAvg (consume)
            buf.readUnsignedByte(); // engineLoadMax (consume)
            buf.readUnsignedByte(); // engineLoadAvg (consume)
            buf.readUnsignedShort(); // odometerTrip (consume)
            buf.readByte(); // coolantTemp (consume)
            buf.readUnsignedShort(); // obdStatus (consume)
            buf.readUnsignedShort(); // obdEvents (consume)
            // If Position model had fuel level/used:
            // position.setFuelLevel(buf.readUnsignedByte());
            // position.setFuelUsed(buf.readUnsignedShort() * 0.1);
            buf.readUnsignedByte(); // fuelLevel (consume)
            buf.readUnsignedShort(); // fuelUsed (consume)
        }

        if ((mask & 16384L) > 0) { // DTC (Diagnostic Trouble Codes)
            for (int j = 1; j <= 5; j++) {
                buf.readCharSequence(5, StandardCharsets.US_ASCII); // dtc (consume)
            }
        }

        if ((mask & 32768L) > 0) { // Extended Odometer/Hours
            buf.readUnsignedMedium(); // Odometer (consume)
            buf.readUnsignedShort(); // Hours (consume)
            buf.readUnsignedShort(); // axleWeight (consume)
            buf.readUnsignedShort(); // tripFuelUsed (consume)
            buf.readUnsignedShort(); // tripCruise (consume)
            buf.readUnsignedShort(); // serviceOdometer (consume)
        }

        if ((mask & 65536L) > 0) { // More Odometer/Hours
            buf.readUnsignedMedium(); // Odometer (consume)
            buf.readUnsignedShort(); // Hours (consume)
            buf.readUnsignedShort(); // time with mil on (consume)
            buf.readUnsignedShort(); // distance with mil on (consume)
        }

        if ((mask & 131072L) > 0) { // Temperature Sensors 1
            for (int j = 1; j <= 6; j++) {
                buf.readShort(); // temp (consume)
            }
            for (int j = 1; j <= 3; j++) {
                buf.readByte(); // setpoint (consume)
            }
            buf.readUnsignedByte(); // refrigerator fuel level (consume)
            buf.readUnsignedShort(); // refrigerator total engine hours (consume)
            buf.readUnsignedShort(); // refrigerator total standby hours (consume)
            buf.readUnsignedShort(); // refrigerator status (consume)
            buf.readUnsignedMedium(); // alarm flags (consume)
        }

        if ((mask & 262144L) > 0) { // Temperature Sensors 2
            for (int j = 1; j <= 4; j++) {
                buf.readUnsignedShort(); // temp (consume)
            }
        }

        if ((mask & 524288L) > 0) { // Alarm Data
            buf.readUnsignedByte(); // alarmCount (consume)
            buf.readSlice(16); // alarmQueue (consume)
        }

        if ((mask & 4294967296L) > 0) { // Generic Sensors
            for (int j = 1; j <= 6; j++) {
                buf.readUnsignedMedium(); // sensor (consume)
            }
        }
        // End of mask processing

        return position;
    }

    @Override
    public byte[] generateResponse(Position position) {
        // Simple acknowledgment (ACK) byte 0x06
        return new byte[]{0x06};
    }
}