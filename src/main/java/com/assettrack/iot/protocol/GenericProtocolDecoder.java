// GenericProtocolDecoder.java
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
                                  @Autowired(required = false) TeltonikaHandler teltonikaHandler,
                                  @Autowired(required = false) Gt06Handler gt06Handler) {
        super(sessionManager, protocolDetector, teltonikaHandler, gt06Handler);
    }

    @Override
    protected DeviceMessage handle(byte[] data) throws ProtocolException {
        // This method will be called only for GT06 packets
        logger.debug("Handling GT06 protocol packet. Raw data length: {}", data.length);
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("GT06");

        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Validate GT06 header
            byte header1 = buffer.get();
            byte header2 = buffer.get();
            if (header1 != PROTOCOL_HEADER_1 || header2 != PROTOCOL_HEADER_2) {
                logger.warn("Invalid GT06 header. Expected {}{} but got {}{}",
                        String.format("%02X", PROTOCOL_HEADER_1), String.format("%02X", PROTOCOL_HEADER_2),
                        String.format("%02X", header1), String.format("%02X", header2));
                throw new ProtocolException("Invalid GT06 header");
            }
            logger.debug("GT06 header validated successfully.");

            int length = buffer.get() & 0xFF;
            byte protocol = buffer.get();
            logger.debug("GT06 Packet: Length={}, Protocol={}", length, String.format("%02X", protocol));

            switch (protocol) {
                case 0x01: // Login packet
                    message.setMessageType("LOGIN");
                    byte[] imeiBytes = new byte[8];
                    buffer.get(imeiBytes);
                    String imei = extractImei(imeiBytes);
                    message.setImei(imei);
                    logger.info("GT06 Login packet received. IMEI: {}", imei);
                    break;

                case 0x12: // GPS data
                    message.setMessageType("GPS");
                    Position position = parseGpsData(buffer);
                    message.addParsedData("position", position);
                    // Assuming position.getDevice() or similar will provide IMEI or can be set later
                    // For now, set IMEI to a placeholder if not derived from position.
                    // You might need to retrieve IMEI from session or previously set in message.
                    if (position.getDevice() != null && position.getDevice().getImei() != null) {
                        message.setImei(position.getDevice().getImei());
                    } else {
                        // Fallback or retrieve from session later
                        logger.warn("IMEI not directly available from GPS data. Will attempt to retrieve from session.");
                    }
                    logger.info("GT06 GPS data packet received. Position: ({},{})",
                            position.getLatitude(), position.getLongitude());
                    break;

                // Add other GT06 protocol cases here as needed
                // case 0x13: // Heartbeat
                //     message.setMessageType("HEARTBEAT");
                //     logger.info("GT06 Heartbeat received.");
                //     break;
                // case 0x16: // Alarm
                //     message.setMessageType("ALARM");
                //     Position alarmPosition = parseGpsData(buffer); // assuming alarm has GPS data
                //     message.addParsedData("position", alarmPosition);
                //     logger.warn("GT06 Alarm received. Position: ({},{})", alarmPosition.getLatitude(), alarmPosition.getLongitude());
                //     break;

                default:
                    message.setMessageType("UNKNOWN_GT06");
                    logger.warn("Unknown GT06 protocol type: {}", String.format("%02X", protocol));
                    break;
            }

            return message;
        } catch (Exception e) {
            logger.error("GT06 decoding failed in handle method: {}", e.getMessage(), e);
            throw new ProtocolException("GT06 decoding failed", e);
        }
    }

    // This override for decode is now redundant if BaseProtocolDecoder handles routing.
    // If you intend for GenericProtocolDecoder to still have a specific 'decode' logic that's different
    // from BaseProtocolDecoder's default, you might keep it, but ensure it aligns with the routing.
    // For now, I'm assuming BaseProtocolDecoder's decode handles the routing, and this 'handle'
    // method is what's called for GT06.
    /*
    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buf, ProtocolDetector.ProtocolDetectionResult result) {
        // This method will now strictly handle GT06 processing, as BaseProtocolDecoder will route Teltonika.
        // The logic here is similar to what was moved to BaseProtocolDecoder's default decode method.
        // Consider if this method is truly needed here or if 'handle(byte[] data)' is sufficient
        // once BaseProtocolDecoder routes.
        // For now, let's assume this method is effectively superseded by BaseProtocolDecoder's routing.
        return super.decode(ctx, buf, result); // Call the superclass's decode which now handles GT06 logic.
    }
    */

    // This method is now implicitly called by BaseProtocolDecoder's logic if Teltonika is detected
    // private DeviceMessage handleTeltonikaImei(ChannelHandlerContext ctx, byte[] data) { ... }
    // This method is now implicitly called by TeltonikaHandler if Teltonika heartbeat is detected
    // private DeviceMessage handleHeartbeat(ChannelHandlerContext ctx) { ... }

    private byte[] generateGt06Response(DeviceMessage message) {
        if ("LOGIN".equals(message.getMessageType())) {
            return generateLoginResponse((short) 1); // Default serial number
        } else if ("GPS".equals(message.getMessageType())) {
            return generateAckResponse();
        }
        // Add responses for other GT06 message types if necessary
        return null;
    }
}