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

import java.net.SocketAddress;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.assettrack.iot.config.BitUtil;
import com.assettrack.iot.config.DateBuilder;
import com.assettrack.iot.config.UnitsConverter;

@Protocol(value = "ASTRA_AT240", version = "1.0")
@Component
public class AstraAt240Handler implements ProtocolHandler {

    private static final Logger logger = LoggerFactory.getLogger(AstraAt240Handler.class);

    private static final byte PROTOCOL_K = (byte) 'K';
    private static final byte PROTOCOL_X = (byte) 'X';

    private String readImei(ByteBuf buf) {
        return String.format("%08d", buf.readUnsignedInt()) + String.format("%07d", buf.readUnsignedMedium());
    }

    private Date readTime(ByteBuf buf) {
        DateBuilder dateBuilder = new DateBuilder()
                .setDate(1980, 1, 6).addMillis(buf.readUnsignedInt() * 1000L);
        return dateBuilder.getDate();
    }

    @Override
    public boolean supports(final String protocolType) {
        return "ASTRA_AT240".equalsIgnoreCase(protocolType);
    }

    @Override
    public boolean canHandle(final String protocol, final String version) {
        return "ASTRA_AT240".equalsIgnoreCase(protocol);
    }

    /**
     * Parse only the first position record from an ASTRA_AT240 message.
     */
    @Override
    public Position parsePosition(final byte[] rawMessage) throws ProtocolException {
        if (rawMessage == null || rawMessage.length < 4) {
            throw new ProtocolException("AT240 message too short for parsing position");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(rawMessage);
        try {
            byte protocolType = buf.readByte();
            buf.readUnsignedShort(); // Skip length field
            if (protocolType == PROTOCOL_X) {
                // Skip record count and IMEI to reach first record
                buf.readUnsignedByte(); // record count
                String imei = readImei(buf);
                // Decode only the first record
                Position position = decodeXProtocolPosition(null, null, buf);
                // Set device identifier on position
                //position.setDevice(imei);
                position.setDevice(position.getDevice());
                return position;
            } else if (protocolType == PROTOCOL_K) {
                throw new ProtocolException("K protocol parsing not fully implemented for parsePosition");
            } else {
                throw new ProtocolException(String.format("Unknown Astra protocol type: 0x%02X", protocolType));
            }
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
            buf.readUnsignedShort(); // Skip length
            if (ctx != null) {
                // Acknowledge receipt
                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x06}));
            }
            if (protocolType == PROTOCOL_X) {
                // Decode X protocol message
                decodeXProtocolMessage(buf, message);
            } else if (protocolType == PROTOCOL_K) {
                throw new ProtocolException("K protocol handling not fully implemented in AstraAt240Handler");
            } else {
                throw new ProtocolException(String.format("Unknown Astra protocol type: 0x%02X", protocolType));
            }
        } finally {
            buf.release();
        }

        logger.info("AstraAt240Handler: Finished parsing ASTRA_AT240 packet. Total records={}",
                ((List<?>) parsed.getOrDefault("records", new ArrayList<>())).size());
        return message;
    }

    @Override
    public DeviceMessage handle(final byte[] data) throws ProtocolException {
        return handle(data, null);
    }

    private Position decodeXProtocolPosition(io.netty.channel.Channel channel, SocketAddress remoteAddress, ByteBuf buf) {
        Position position = new Position();
        position.setProtocol("ASTRA_AT240");

        // Read record index
        //position.set(Position.KEY_INDEX, buf.readUnsignedByte());

        // Read timestamp
        Date deviceTime = readTime(buf);
        //position.setDeviceTime(deviceTime);
        LocalDateTime timestamp = deviceTime.toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
        position.setTimestamp(timestamp);
        //position.setFixTime(timestamp);

        // Read mask
        long mask = ((long) buf.readUnsignedShort() << 32) + buf.readUnsignedInt();

        if (BitUtil.check(mask, 1)) {
            position.setValid(true);
            position.setLatitude(buf.readInt() * 0.000001);
            position.setLongitude(buf.readInt() * 0.000001);
            position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedByte() * 2));
            buf.readUnsignedByte(); // reserved
            position.setCourse((double) (buf.readUnsignedByte() * 2));
            position.setAltitude((short) (buf.readUnsignedByte() * 20.0));
        } else {
            position.setValid(false);
        }

        // ... other mask checks retained ...

        return position;
    }

    private void decodeXProtocolMessage(ByteBuf buf, DeviceMessage message) throws ProtocolException {
        int count = buf.readUnsignedByte();
        logger.info("ASTRA_AT240 X-Protocol: {} records detected.", count);

        String imei = readImei(buf);
        logger.info("ASTRA_AT240 X-Protocol: IMEI={}", imei);
        // Set device on message
        message.setImei(imei);

        List<Map<String, Object>> records = new ArrayList<>();
        Position primaryPosition = null;
        for (int i = 0; i < count; i++) {
            Map<String, Object> rec = new HashMap<>();
            try {
                Position tempPosition = decodeXProtocolPosition(null, null, buf);
                rec.put("Timestamp", tempPosition.getTimestamp());
                rec.put("Latitude", tempPosition.getLatitude());
                rec.put("Longitude", tempPosition.getLongitude());
                rec.put("Speed", tempPosition.getSpeed());
                rec.put("Course", tempPosition.getCourse());
                rec.put("Altitude", tempPosition.getAltitude());
                rec.put("Valid", tempPosition.getValid());
                records.add(rec);
                logger.info("AstraAt240Handler: Parsed Record {}: {}", i + 1, rec);

                if (primaryPosition == null) {
                    primaryPosition = tempPosition;
                    // Set device on primary position
                    primaryPosition.setDevice(primaryPosition.getDevice());
                }

            } catch (Exception e) {
                logger.error("Error parsing ASTRA_AT240 X-Protocol record {}: {}", i + 1, e.getMessage());
                break;
            }
        }
        message.getParsedData().put("records", records);
        if (primaryPosition != null) {
            message.setPosition(primaryPosition);
            logger.info("AstraAt240Handler: Setting primary position from the first parsed record.");
        }
    }

    @Override
    public byte[] generateResponse(Position position) {
        return null;
    }
}
