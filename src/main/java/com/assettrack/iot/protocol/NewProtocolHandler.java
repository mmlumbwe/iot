package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

@Component
@Protocol(value = "NEW_PROTOCOL", version = "1.0")
public class NewProtocolHandler implements ProtocolHandler {
    private static final Logger logger = LoggerFactory.getLogger(NewProtocolHandler.class);
    private static final int HEADER_SIZE = 8;
    private static final byte[] SIGNATURE = {0x00, 0x00, 0x00, 0x33};

    @Override
    public boolean canHandle(String protocol, String version) {
        return "NEW_PROTOCOL".equalsIgnoreCase(protocol) && "1.0".equals(version);
    }

    @Override
    public Position parsePosition(byte[] rawMessage) {
        return null;
    }

    @Override
    public byte[] generateResponse(Position position) {
        return new byte[0];
    }

    @Override
    public boolean supports(String protocolType) {
        return false;
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("NEW_PROTOCOL");

        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Validate header
            for (int i = 0; i < SIGNATURE.length; i++) {
                if (buffer.get() != SIGNATURE[i]) {
                    throw new ProtocolException("Invalid packet signature");
                }
            }

            // Parse packet structure
            int dataLength = buffer.getInt();
            int packetType = buffer.get() & 0xFF;

            message.setMessageType(packetType == 0x01 ? "LOCATION" : "DATA");

            // Parse position if available
            if (packetType == 0x01 && buffer.remaining() >= 28) {
                Position position = parsePosition(buffer);
                message.addParsedData("position", position);
            }

            // Generate response
            message.addParsedData("response", new byte[]{0x01}); // ACK
            return message;

        } catch (Exception e) {
            throw new ProtocolException("Failed to parse NEW_PROTOCOL packet", e);
        }
    }

    private Position parsePosition(ByteBuffer buffer) {
        Position position = new Position();
        Device device = new Device();

        // Parse position data (adjust based on actual protocol spec)
        position.setLatitude((double) buffer.getFloat());
        position.setLongitude((double) buffer.getFloat());
        position.setSpeed((double) (buffer.getShort() & 0xFFFF));
        position.setValid((buffer.get() & 0x01) == 1);

        // Set device info if available
        if (buffer.remaining() >= 15) {
            byte[] imeiBytes = new byte[15];
            buffer.get(imeiBytes);
            device.setImei(new String(imeiBytes, StandardCharsets.US_ASCII));
        }

        position.setDevice(device);
        return position;
    }
}
