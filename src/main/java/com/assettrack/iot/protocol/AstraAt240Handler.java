package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import io.netty.channel.ChannelHandlerContext;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Protocol(value = "ASTRA_AT240", version = "1.0")
@Component
public class AstraAt240Handler implements ProtocolHandler {

    private static final Logger logger = LoggerFactory.getLogger(AstraAt240Handler.class);

    // AT240 header bytes
    private static final byte HEADER_FIRST = 0x58;
    private static final byte HEADER_SECOND = 0x02;

    @Override
    public boolean supports(final String protocolType) {
        return "ASTRA_AT240".equalsIgnoreCase(protocolType);
    }

    @Override
    public boolean canHandle(final String protocol, final String version) {
        return "ASTRA_AT240".equalsIgnoreCase(protocol);
    }

    /**
     * Parse a single Position from the raw AT240 packet. Uses the first record.
     */
    @Override
    public Position parsePosition(final byte[] rawMessage) throws ProtocolException {
        if (rawMessage == null || rawMessage.length < 6) {
            throw new ProtocolException("AT240 message too short for parsing position");
        }
        // Change ByteOrder to LITTLE_ENDIAN
        ByteBuffer buffer = ByteBuffer.wrap(rawMessage).order(ByteOrder.LITTLE_ENDIAN);
        // Skip header (0x58,0x02)
        byte h1 = buffer.get();
        byte h2 = buffer.get();
        if (h1 != HEADER_FIRST || h2 != HEADER_SECOND) {
            throw new ProtocolException("Invalid AT240 header for parsePosition");
        }
        int length = buffer.get() & 0xFF;
        int recordCount = buffer.get() & 0xFF;
        if (recordCount < 1) {
            throw new ProtocolException("No records to parse in AT240 message");
        }

        Position position = new Position();
        position.setProtocol("ASTRA_AT240");

        // Assuming a common AT240 record structure for the first record
        // These offsets are illustrative and may need adjustment based on exact protocol spec
        // Timestamp (4 bytes, Unix epoch)
        long timestamp = Integer.toUnsignedLong(buffer.getInt());
        position.setTimestamp(LocalDateTime.ofEpochSecond(timestamp, 0, ZoneOffset.UTC));

        // Latitude (4 bytes, scaled integer)
        double latitude = buffer.getInt() / 1000000.0;
        position.setLatitude(latitude);

        // Longitude (4 bytes, scaled integer)
        double longitude = buffer.getInt() / 1000000.0;
        position.setLongitude(longitude);

        // Speed (2 bytes, km/h)
        position.setSpeed((double) (buffer.getShort() & 0xFFFF));

        // Course (2 bytes, degrees)
        position.setCourse((double) (buffer.getShort() & 0xFFFF));

        // Altitude (2 bytes, meters)
        position.setAltitude(buffer.getShort());

        // Satellites (1 byte)
        position.setSatellites(buffer.get() & 0xFF);

        // HDOP (1 byte, scaled by 0.1)
        //position.setHdop((buffer.get() & 0xFF) * 0.1);

        // Ignition Status (1 byte, bit 0)
        boolean ignition = ((buffer.get() & 0xFF) & 0x01) == 0x01;
        //position.set("ignition", ignition ? "ON" : "OFF");

        return position;
    }

    /**
     * Full message handler: parses all records into parsedData map.
     */
    @Override
    public DeviceMessage handle(final byte[] data, final ChannelHandlerContext ctx) throws ProtocolException {
        logger.info("Processing ASTRA_AT240 packet, length={} bytes", data.length);
        if (data.length < 4 || data[0] != HEADER_FIRST || data[1] != HEADER_SECOND) {
            throw new ProtocolException(String.format(
                    "Invalid AT240 header: 0x%02X 0x%02X", data[0], data[1]
            ));
        }
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("ASTRA_AT240");
        message.setMessageType("DATA");

        Map<String, Object> parsed = new HashMap<>();
        message.setParsedData(parsed);

        // Change ByteOrder to LITTLE_ENDIAN
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        // Skip header
        buffer.get(); // 0x58
        buffer.get(); // 0x02
        int length = buffer.get() & 0xFF;
        int recordCount = buffer.get() & 0xFF;
        parsed.put("length", length);
        parsed.put("recordCount", recordCount);

        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < recordCount; i++) {
            if (buffer.remaining() <= 0) {
                logger.warn("Buffer exhausted before parsing all records. Expected {}, parsed {}", recordCount, i);
                break;
            }
            Map<String, Object> rec = new HashMap<>();

            // Parse each record based on common AT240 protocol structure
            long timestamp = Integer.toUnsignedLong(buffer.getInt());
            LocalDateTime dateTime = LocalDateTime.ofEpochSecond(timestamp, 0, ZoneOffset.UTC);
            rec.put("Timestamp", dateTime.toString());
            logger.info("Record {}: Timestamp={}", i + 1, dateTime);

            double latitude = buffer.getInt() / 1000000.0;
            rec.put("Latitude", latitude);
            logger.info("Record {}: Latitude={}", i + 1, latitude);

            double longitude = buffer.getInt() / 1000000.0;
            rec.put("Longitude", longitude);
            logger.info("Record {}: Longitude={}", i + 1, longitude);

            int speed = buffer.getShort() & 0xFFFF;
            rec.put("Speed", speed);
            logger.info("Record {}: Speed={} km/h", i + 1, speed);

            int course = buffer.getShort() & 0xFFFF;
            rec.put("Course", course);
            logger.info("Record {}: Course={} degrees", i + 1, course);

            short altitude = buffer.getShort();
            rec.put("Altitude", altitude);
            logger.info("Record {}: Altitude={} m", i + 1, altitude);

            int satellites = buffer.get() & 0xFF;
            rec.put("Satellites", satellites);
            logger.info("Record {}: Satellites={}", i + 1, satellites);

            double hdop = (buffer.get() & 0xFF) * 0.1;
            rec.put("HDOP", hdop);
            logger.info("Record {}: HDOP={}", i + 1, hdop);

            boolean ignition = ((buffer.get() & 0xFF) & 0x01) == 0x01;
            rec.put("Ignition Status", ignition ? "ON" : "OFF");
            logger.info("Record {}: Ignition Status={}", i + 1, (ignition ? "ON" : "OFF"));

            // Reserved data (assuming 4 bytes based on typical structures, adjust if needed)
            byte[] reservedData = new byte[4];
            buffer.get(reservedData);
            rec.put("Reserved Data", bytesToHex(reservedData));
            logger.info("Record {}: Reserved Data={}", i + 1, bytesToHex(reservedData));


            records.add(rec);
            logger.info("AstraAt240Handler: Parsed Record {}: Lat={}, Lon={}, Speed={} km/h, Course={} deg, Alt={} m, Sats={}, Ignition={}",
                    i + 1, latitude, longitude, speed, course, altitude, satellites, (ignition ? "ON" : "OFF"));
        }
        parsed.put("records", records);

        // Set primary position from the first record if available
        if (!records.isEmpty()) {
            Map<String, Object> firstRecord = records.get(0);
            Position primaryPosition = new Position();
            primaryPosition.setProtocol("ASTRA_AT240");
            primaryPosition.setTimestamp(LocalDateTime.parse((String) firstRecord.get("Timestamp")));
            primaryPosition.setLatitude((Double) firstRecord.get("Latitude"));
            primaryPosition.setLongitude((Double) firstRecord.get("Longitude"));
            primaryPosition.setSpeed(((Integer) firstRecord.get("Speed")).doubleValue());
            primaryPosition.setCourse(((Integer) firstRecord.get("Course")).doubleValue());
            primaryPosition.setAltitude((short) ((Short) firstRecord.get("Altitude")).doubleValue());
            primaryPosition.setSatellites((Integer) firstRecord.get("Satellites"));
            //primaryPosition.setHdop((Double) firstRecord.get("HDOP"));
            //primaryPosition.set("ignition", firstRecord.get("Ignition Status"));
            message.setPosition(primaryPosition);
            logger.info("AstraAt240Handler: Setting primary position from the first parsed record.");
        }

        message.setResponseRequired(false);
        message.setResponseData(null);

        logger.info("AstraAt240Handler: Finished parsing ASTRA_AT240 packet. Total records={}", records.size());
        return message;
    }

    @Override
    public DeviceMessage handle(final byte[] data) throws ProtocolException {
        return handle(data, null);
    }

    /**
     * AT240 does not require a per-position response for data packets,
     * so this method returns null.
     */
    @Override
    public byte[] generateResponse(Position position) {
        return null;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}