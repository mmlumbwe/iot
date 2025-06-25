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
import java.time.LocalDateTime; // Added import for LocalDateTime
import java.sql.Timestamp; // Added import for Timestamp
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.codec.binary.Hex; // Added import for Hex encoding

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
            throw new ProtocolException("Invalid AT240 message for position parsing: too short or null.");
        }
        // This method assumes rawMessage is a full AT240 packet or a single record's byte array.
        // For multi-record packets, this method should be called on individual record byte arrays.
        // For simplicity and to avoid re-parsing the header/record count, we'll extract the relevant
        // record data if this method is called with a full packet.
        // However, the main parsing logic is now in the handle method.
        // This method might be called by BaseProtocolDecoder in a fallback scenario,
        // so it should be able to parse a single record if given.

        // This implementation will assume it receives the byte array of a *single* record for parsing
        // and is primarily used for internal Position object creation from the parsed data in handle().
        // If it's expected to parse from the full message header, this needs to be adapted.

        // For now, let's just create a dummy position to avoid null pointer,
        // as the actual position parsing happens in the handle method.
        // A more robust solution would be to delegate to the record parsing logic here too if needed.
        Position position = new Position();
        position.setValid(false);
        position.setTimestamp(new Timestamp(System.currentTimeMillis()).toLocalDateTime());
        position.setLatitude(0.0);
        position.setLongitude(0.0);
        position.setSpeed((double) 0);
        position.setCourse((double) 0);
        position.setAltitude((short) 0.0);
        //position.setAttributes(new HashMap<>());
        logger.warn("AstraAt240Handler.parsePosition(byte[] rawMessage) called. Actual parsing is handled in handle() method for multiple records.");
        return position;
    }

    @Override
    public DeviceMessage handle(final byte[] data, ChannelHandlerContext ctx) throws ProtocolException {
        logger.info("AstraAt240Handler: Processing ASTRA_AT240 packet, length={} bytes", data.length);
        if (data == null || data.length < 6 || data[0] != HEADER_FIRST || data[1] != HEADER_SECOND) {
            throw new ProtocolException(String.format(
                    "Invalid AT240 header: 0x%02X 0x%02X", data[0], data[1]
            ));
        }

        DeviceMessage message = new DeviceMessage();
        message.setProtocol("ASTRA_AT240");
        message.setMessageType("DATA"); // Default message type for data packets

        Map<String, Object> parsed = new HashMap<>();
        message.setParsedData(parsed);

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        // Skip header
        buffer.get(); // 0x58
        buffer.get(); // 0x02

        int totalPayloadLength = buffer.get() & 0xFF; // Third byte: Total payload length (after header)
        int recordCount = buffer.get() & 0xFF;        // Fourth byte: Number of records
        parsed.put("totalPayloadLength", totalPayloadLength);
        parsed.put("recordCount", recordCount);

        logger.info("AstraAt240Handler: Packet details - Total payload length={}, Number of records={}", totalPayloadLength, recordCount);


        List<Map<String, Object>> recordsList = new ArrayList<>(); // List to store raw parsed record data as maps
        List<Position> parsedPositions = new ArrayList<>(); // List to store parsed Position objects

        for (int i = 0; i < recordCount; i++) {
            logger.info("AstraAt240Handler: Starting to parse record {}/{} from packet.", i + 1, recordCount);
            // Assuming a fixed record length of 27 bytes for each ASTRA AT240 record
            // This is a common structure for some AT240 variants. Adjust if documentation specifies otherwise.
            if (buffer.remaining() < 27) {
                logger.warn("AstraAt240Handler: Insufficient bytes remaining for record {} ({} bytes remaining, {} expected). Skipping remaining records.", i + 1, buffer.remaining(), 27);
                break;
            }

            Map<String, Object> rec = new HashMap<>(); // Map for current record's raw parsed data

            // Parse Timestamp (4 bytes, Unix epoch seconds, unsigned int)
            long timestampSeconds = buffer.getInt() & 0xFFFFFFFFL;
            // Convert epoch seconds to LocalDateTime, assuming UTC for ASTRA devices
            LocalDateTime dateTime = LocalDateTime.ofEpochSecond(timestampSeconds, 0, java.time.ZoneOffset.UTC);
            rec.put("timestampEpoch", timestampSeconds);
            rec.put("timestamp", dateTime.toString());
            logger.info("Record {}: Timestamp={}", i + 1, dateTime.toString());

            // Parse Latitude (4 bytes, signed integer, scaled by 10^6)
            int latInt = buffer.getInt();
            double latitude = latInt / 1000000.0; // Convert to double degrees
            rec.put("latitude", latitude);
            logger.info("Record {}: Latitude={}", i + 1, latitude);

            // Parse Longitude (4 bytes, signed integer, scaled by 10^6)
            int lonInt = buffer.getInt();
            double longitude = lonInt / 1000000.0; // Convert to double degrees
            rec.put("longitude", longitude);
            logger.info("Record {}: Longitude={}", i + 1, longitude);

            // Parse Speed (2 bytes, unsigned short, in km/h)
            int speed = buffer.getShort() & 0xFFFF; // Convert to unsigned short
            rec.put("speed", speed);
            logger.info("Record {}: Speed={} km/h", i + 1, speed);

            // Parse Course (2 bytes, unsigned short, degrees)
            int course = buffer.getShort() & 0xFFFF; // Convert to unsigned short
            rec.put("course", course);
            logger.info("Record {}: Course={} degrees", i + 1, course);

            // Parse Altitude (2 bytes, signed short, in meters)
            int altitude = buffer.getShort();
            rec.put("altitude", altitude);
            logger.info("Record {}: Altitude={} m", i + 1, altitude);

            // Parse Number of Satellites (1 byte, unsigned)
            int satellites = buffer.get() & 0xFF; // Convert to unsigned byte
            rec.put("satellites", satellites);
            logger.info("Record {}: Satellites={}", i + 1, satellites);

            // Parse HDOP (1 byte, unsigned, scaled by 10, e.g., actual HDOP * 10)
            int hdop = buffer.get() & 0xFF; // Convert to unsigned byte
            rec.put("hdop", hdop / 10.0); // Store actual HDOP value
            logger.info("Record {}: HDOP={}", i + 1, hdop / 10.0);

            // Parse Ignition status (1 byte, unsigned)
            int ignitionStatusRaw = buffer.get() & 0xFF;
            rec.put("ignitionStatusRaw", ignitionStatusRaw);
            boolean ignitionOn = (ignitionStatusRaw & 0x01) != 0; // Assuming LSB (bit 0) indicates ignition
            rec.put("ignitionOn", ignitionOn);
            logger.info("Record {}: Ignition Status={}", i + 1, ignitionOn ? "ON" : "OFF");

            // Consume remaining 6 bytes for a 27-byte record (Reserved/Other data)
            byte[] reservedBytes = new byte[6];
            buffer.get(reservedBytes);
            rec.put("reservedData", Hex.encodeHexString(reservedBytes)); // Hex representation of reserved bytes
            logger.info("Record {}: Reserved Data={}", i + 1, Hex.encodeHexString(reservedBytes));

            recordsList.add(rec); // Add the map of raw parsed record data to the list

            // Create a Position object for each parsed record
            Position position = new Position();
            position.setTimestamp(Timestamp.valueOf(dateTime).toLocalDateTime());
            position.setLatitude(latitude);
            position.setLongitude(longitude);
            position.setSpeed((double) speed);
            position.setCourse((double) course);
            position.setAltitude((short) altitude);
            position.setValid(satellites > 0); // Consider position valid if satellites are detected

            // Store all parsed attributes in the Position object's attributes map for detailed access
            /*position.setAttributes(new HashMap<>(rec)); // Copy all record details

            // Add specific attributes to Position's main attributes if desired
            position.getAttributes().put("satellites", satellites);
            position.getAttributes().put("hdop", hdop / 10.0);
            position.getAttributes().put("ignitionOn", ignitionOn);*/

            parsedPositions.add(position); // Add the Position object to the list
            logger.info("AstraAt240Handler: Parsed Record {}: Lat={}, Lon={}, Speed={} km/h, Course={} deg, Alt={} m, Sats={}, Ignition={}",
                    i + 1, latitude, longitude, speed, course, altitude, satellites, ignitionOn ? "ON" : "OFF");
        }

        parsed.put("records", recordsList); // Add the list of raw parsed record maps to the DeviceMessage's parsedData

        // Set the primary position for the DeviceMessage. Typically, this is the first record.
        if (!parsedPositions.isEmpty()) {
            message.setPosition(parsedPositions.get(0)); // Assuming DeviceMessage has a setPosition method
            logger.info("AstraAt240Handler: Setting primary position from the first parsed record.");
        } else {
            logger.warn("AstraAt240Handler: No valid positions were parsed from the packet.");
        }
        // If DeviceMessage class supports a list of positions, you can set it here:
        // message.setPositions(parsedPositions); // Uncomment if DeviceMessage has setPositions(List<Position>)

        logger.info("AstraAt240Handler: Finished parsing ASTRA_AT240 packet. Total records={}", recordsList.size());

        message.setResponseRequired(false); // AT240 typically does not require a response for data packets
        message.setResponseData(null); // No response data generated by default for data packets

        return message;
    }

    @Override
    public DeviceMessage handle(final byte[] data) throws ProtocolException {
        // This method might be called without a ChannelHandlerContext,
        // e.g., for unit testing or specific offline processing.
        // It will call the main handle method with a null context.
        return handle(data, null);
    }

    /**
     * AT240 does not require a per-position response in the traditional sense for data packets.
     * Responses are typically for initial connection or command acknowledgments.
     */
    @Override
    public byte[] generateResponse(Position position) {
        logger.warn("AstraAt240Handler: generateResponse(Position) called. AT240 protocol typically does not generate responses per position.");
        return new byte[0]; // Return empty byte array indicating no response needed
    }
}