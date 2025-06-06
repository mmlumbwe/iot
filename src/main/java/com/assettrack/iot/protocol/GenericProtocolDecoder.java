package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.session.SessionManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.codec.binary.Hex;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Component
@ChannelHandler.Sharable
public class GenericProtocolDecoder extends BaseProtocolDecoder {
    private static final Logger logger = LoggerFactory.getLogger(GenericProtocolDecoder.class);

    @Autowired
    public GenericProtocolDecoder(SessionManager sessionManager,
                                  ProtocolDetector protocolDetector,
                                  @Autowired(required = false) TeltonikaHandler teltonikaHandler) {
        super(sessionManager, protocolDetector, teltonikaHandler);
    }

    @Override
    protected DeviceMessage handle(byte[] data) throws ProtocolException {
        // Handle GT06 protocol packets
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("GT06");

        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Validate GT06 header
            if (buffer.get() != PROTOCOL_HEADER_1 || buffer.get() != PROTOCOL_HEADER_2) {
                throw new ProtocolException("Invalid GT06 header");
            }

            int length = buffer.get() & 0xFF;
            byte protocol = buffer.get();

            switch (protocol) {
                case 0x01: // Login packet
                    message.setMessageType("LOGIN");
                    byte[] imeiBytes = new byte[8];
                    buffer.get(imeiBytes);
                    message.setImei(extractImei(imeiBytes));
                    break;

                case 0x12: // GPS data
                    message.setMessageType("GPS");
                    Position position = parseGpsData(buffer);
                    message.addParsedData("position", position);
                    message.setImei(position.getDevice().getImei());
                    break;

                default:
                    message.setMessageType("UNKNOWN");
                    break;
            }

            return message;
        } catch (Exception e) {
            throw new ProtocolException("GT06 decoding failed", e);
        }
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buf, ProtocolDetector.ProtocolDetectionResult result) {
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data); // Now consume the buffer

            // Only handle GT06 packets here (Teltonika handled in ProtocolDetectionHandler)
            if (result == null || !"GT06".equals(result.getProtocol())) {
                if (isValidGT06Header(data)) {
                    result = ProtocolDetector.ProtocolDetectionResult.success("GT06", "LOGIN", "1.0");
                    logger.debug("Manually detected GT06 packet");
                } else {
                    logger.debug("Unknown protocol packet");
                    return null;
                }
            }

            // Process GT06 packet
            DeviceMessage message = handle(data);
            if (message != null) {
                enrichMessageWithContext(ctx, message);
                byte[] response = generateGt06Response(message);
                if (response != null) {
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
                }
                return message;
            }
        } catch (Exception e) {
            logger.error("GT06 decoding error: {}", e.getMessage(), e);
        }
        return null;
    }

    private DeviceMessage handleTeltonikaImei(ChannelHandlerContext ctx, byte[] data) {
        try {
            // Extract IMEI from Teltonika packet (first 2 bytes are length)
            int length = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            String imei = new String(data, 2, length, StandardCharsets.US_ASCII);
            imei = imei.replaceAll("[^0-9]", "");

            if (imei.length() >= 15) {
                imei = imei.substring(0, 15); // Take first 15 digits

                DeviceMessage message = new DeviceMessage();
                message.setProtocol("TELTONIKA");
                message.setMessageType("IMEI");
                message.setImei(imei);

                // Send Teltonika login response (0x01)
                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x01}));
                logger.info("Accepted Teltonika IMEI: {}", imei);

                enrichMessageWithContext(ctx, message);
                return message;
            }
        } catch (Exception e) {
            logger.error("Failed to process Teltonika IMEI", e);
        }
        return null;
    }

    private DeviceMessage handleHeartbeat(ChannelHandlerContext ctx) {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("TELTONIKA");
        message.setMessageType("HEARTBEAT");
        ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0x00}));
        return message;
    }

    private byte[] generateGt06Response(DeviceMessage message) {
        if ("LOGIN".equals(message.getMessageType())) {
            return generateLoginResponse((short) 1); // Default serial number
        } else if ("GPS".equals(message.getMessageType())) {
            return generateAckResponse();
        }
        return null;
    }
}