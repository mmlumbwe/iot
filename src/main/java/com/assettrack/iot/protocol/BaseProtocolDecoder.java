package com.assettrack.iot.protocol;

import com.assettrack.iot.config.Checksum;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.session.SessionManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.AttributeKey; //
import io.netty.util.ReferenceCountUtil; //
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.assettrack.iot.session.DeviceSession;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Component
@ChannelHandler.Sharable
public abstract class BaseProtocolDecoder extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(BaseProtocolDecoder.class);

    // Protocol constants (common to some protocols like GT06)
    protected static final byte PROTOCOL_HEADER_1 = 0x78;
    protected static final byte PROTOCOL_HEADER_2 = 0x78;
    protected static final byte PROTOCOL_LOGIN = 0x01;
    protected static final byte PROTOCOL_GPS = 0x12;
    protected static final byte PROTOCOL_HEARTBEAT = 0x13;
    protected static final byte PROTOCOL_TERMINATOR_1 = 0x0D;
    protected static final byte PROTOCOL_TERMINATOR_2 = 0x0A;
    protected static final byte PROTOCOL_ALARM = 0x16; // Example for GT06 alarm

    // Teltonika specific constants (can be moved to TeltonikaHandler if not used by others)
    protected static final int TELTONIKA_PREAMBLE = 0x00000000;
    protected static final int TELTONIKA_IMEI_HEADER = 0x0F; // Length of IMEI data block
    protected static final byte TELTONIKA_IMEI_ACK = 0x01; // Teltonika IMEI acknowledgement

    protected final SessionManager sessionManager;
    protected final ProtocolDetector protocolDetector; // Used for generic detection if needed here
    protected final TeltonikaHandler teltonikaHandler; // Specific handler for Teltonika
    protected final Gt06Handler gt06Handler; // Specific handler for GT06
    // Add TK103Handler if available
    // protected final Tk103Handler tk103Handler;

    @Autowired
    public BaseProtocolDecoder(
            SessionManager sessionManager,
            ProtocolDetector protocolDetector,
            TeltonikaHandler teltonikaHandler,
            Gt06Handler gt06Handler) {
        this.sessionManager = sessionManager;
        this.protocolDetector = protocolDetector;
        this.teltonikaHandler = teltonikaHandler;
        this.gt06Handler = gt06Handler;
        // this.tk103Handler = tk103Handler; // Inject Tk103Handler
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // BaseProtocolDecoder now primarily expects ByteBuf messages,
        // with protocol detection result already stored by ProtocolDetectionHandler.
        if (!(msg instanceof ByteBuf buf)) {
            logger.warn("BaseProtocolDecoder: Received non-ByteBuf message: {}", msg.getClass().getName());
            ctx.fireChannelRead(msg); // Pass through if not a ByteBuf (e.g., if another handler fires something else)
            return;
        }

        try {
            byte[] rawMessage = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), rawMessage); // Read bytes without advancing readerIndex

            logger.info("Raw-Inbound - [id: {}, L:{}] READ: {}", ctx.channel().id(), ctx.channel().localAddress(), bytesToHex(rawMessage));

            // Retrieve the stored detection result from channel attributes
            ProtocolDetector.ProtocolDetectionResult detectionResult = ctx.channel().attr(ProtocolDetector.PROTOCOL_DETECTION_RESULT_KEY).getAndSet(null); // Get and clear

            if (detectionResult == null || !detectionResult.isDetected()) {
                logger.warn("BaseProtocolDecoder: No valid protocol detection result found in channel attributes for channel {}. Attempting fallback detection.", ctx.channel().id());
                // Fallback: try to detect again if for some reason detection result wasn't set or cleared prematurely
                detectionResult = protocolDetector.detect(rawMessage);
            }

            if (detectionResult != null && detectionResult.isDetected()) {
                ProtocolHandler handler = null;
                if ("TELTONIKA".equalsIgnoreCase(detectionResult.getProtocol())) {
                    handler = teltonikaHandler;
                } else if ("GT06".equalsIgnoreCase(detectionResult.getProtocol())) {
                    handler = gt06Handler;
                }
                // Add TK103 here if handler is implemented
                // else if ("TK103".equalsIgnoreCase(detectionResult.getProtocol())) { handler = tk103Handler; }

                if (handler != null && handler.canHandle(detectionResult.getProtocol(), detectionResult.getVersion())) {
                    logger.info("BaseProtocolDecoder: Dispatching to {} for protocol {} (packetType: {})",
                            handler.getClass().getSimpleName(), detectionResult.getProtocol(), detectionResult.getPacketType());
                    // Call the handle method matching the ProtocolHandler interface: handle(byte[] data, ChannelHandlerContext ctx)
                    handler.handle(rawMessage, ctx);
                } else {
                    logger.warn("BaseProtocolDecoder: No suitable handler found for protocol {} version {} on channel {}. Releasing buffer.",
                            detectionResult.getProtocol(), detectionResult.getVersion(), ctx.channel().id());
                    ReferenceCountUtil.release(buf); // Release if no handler can process it
                }
            } else {
                logger.warn("BaseProtocolDecoder: Unknown or undetected protocol for channel {}. Releasing buffer.", ctx.channel().id());
                ReferenceCountUtil.release(buf); // Release buffer if protocol is not detected
            }
        } catch (Exception e) {
            logger.error("Error in BaseProtocolDecoder for channel {}", ctx.channel().id(), e);
            ReferenceCountUtil.release(buf); // Ensure buffer is released on error
            ctx.close(); // Close channel on decoding error
        } finally {
            ReferenceCountUtil.release(buf); // Ensure the ByteBuf is released after processing in all cases
        }
    }


    protected String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    // Common response generation methods (example, might be overridden by specific handlers)
    protected byte[] generateLoginResponse(short serialNumber) {
        byte[] response = new byte[11]; // 0x78 0x78 0x05 0x01 [serial] 0x01 [CRC] 0x0D 0x0A

        // Header
        response[0] = PROTOCOL_HEADER_1; //
        response[1] = PROTOCOL_HEADER_2; //

        // Packet length (5 bytes)
        response[2] = 0x05;

        // Protocol number (login)
        response[3] = PROTOCOL_LOGIN; //

        // Serial number (big-endian)
        response[4] = (byte) (serialNumber >> 8);
        response[5] = (byte) (serialNumber & 0xFF);

        // Status (success)
        response[6] = 0x01;

        // Calculate CRC
        ByteBuffer crcBuffer = ByteBuffer.wrap(response, 2, 5);
        int crc = Checksum.crc16(Checksum.CRC16_X25, crcBuffer); // Assumes Checksum utility is available

        // Add CRC (big-endian)
        response[7] = (byte) (crc >> 8);
        response[8] = (byte) (crc & 0xFF);

        // Terminator
        response[9] = PROTOCOL_TERMINATOR_1; //
        response[10] = PROTOCOL_TERMINATOR_2; //

        logger.info("Generated login response for serial {}: {}", serialNumber, bytesToHex(response));
        return response;
    }

    protected byte[] generateAckResponse() {
        byte[] response = new byte[10];

        // Header
        response[0] = PROTOCOL_HEADER_1; //
        response[1] = PROTOCOL_HEADER_2; //

        // Packet length (5 bytes)
        response[2] = 0x05;

        // Protocol number (login)
        response[3] = PROTOCOL_LOGIN; //

        // Empty serial number
        response[4] = 0x00;
        response[5] = 0x00;

        // Calculate CRC
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 4);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer); // Assumes Checksum utility is available

        // Add CRC
        response[6] = (byte) (checksum >> 8);
        response[7] = (byte) (checksum & 0xFF);

        // Terminator
        response[8] = PROTOCOL_TERMINATOR_1; //
        response[9] = PROTOCOL_TERMINATOR_2; //

        logger.info("Generated general acknowledgment response: {}", bytesToHex(response));
        return response;
    }
}