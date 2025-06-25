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
        ByteBuffer buffer = ByteBuffer.wrap(rawMessage).order(ByteOrder.BIG_ENDIAN);
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
        // TODO: actual field parsing; using placeholder values
        Position position = new Position();
        position.setProtocol("ASTRA_AT240");
        // Example: set timestamp, latitude, longitude when implemented
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

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
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
                break;
            }
            Map<String, Object> rec = new HashMap<>();
            // TODO: parse each record (timestamp, lat, lon, speed, etc.)
            records.add(rec);
        }
        parsed.put("records", records);

        message.setResponseRequired(false);
        message.setResponseData(null);

        return message;
    }

    @Override
    public DeviceMessage handle(final byte[] data) throws ProtocolException {
        return handle(data, null);
    }

    /**
     * AT240 does not require a per-position ACK.
     */
    @Override
    public byte[] generateResponse(final Position position) {
        return null;
    }
}
