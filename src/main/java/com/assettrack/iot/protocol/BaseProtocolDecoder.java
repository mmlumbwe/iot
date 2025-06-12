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
import io.netty.util.AttributeKey; // Import AttributeKey
import io.netty.util.ReferenceCountUtil;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Component
@ChannelHandler.Sharable
public abstract class BaseProtocolDecoder extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(BaseProtocolDecoder.class);

    // Protocol constants (these seem specific to GT06/TK103 from your other files, might be moved)
    protected static final byte PROTOCOL_HEADER_1 = 0x78;
    protected static final byte PROTOCOL_HEADER_2 = 0x78;
    protected static final byte PROTOCOL_LOGIN = 0x01;
    protected static final byte PROTOCOL_TERMINATOR_1 = 0x0D;
    protected static final byte PROTOCOL_TERMINATOR_2 = 0x0A;

    protected final SessionManager sessionManager;
    protected final ProtocolDetector protocolDetector; // Kept for initial detection if no protocol is set
    protected final TeltonikaHandler teltonikaHandler;
    protected final Gt06Handler gt06Handler;
    // Add other handlers as needed

    // Reuse the same AttributeKey from ProtocolDetectionHandler
    private static final AttributeKey<String> DETECTED_PROTOCOL_KEY = ProtocolDetectionHandler.DETECTED_PROTOCOL_KEY;


    public BaseProtocolDecoder(
            SessionManager sessionManager,
            ProtocolDetector protocolDetector,
            TeltonikaHandler teltonikaHandler,
            Gt06Handler gt06Handler) {
        this.sessionManager = sessionManager;
        this.protocolDetector = protocolDetector;
        this.teltonikaHandler = teltonikaHandler;
        this.gt06Handler = gt06Handler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // Get a copy of the readable bytes from the buffer
        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);

        ProtocolDetector.ProtocolDetectionResult protocolResult;

        // Check if the protocol has already been detected and stored in Channel attributes
        String detectedProtocol = ctx.channel().attr(DETECTED_PROTOCOL_KEY).get();

        if (detectedProtocol != null) {
            // Protocol already detected (e.g., by ProtocolDetectionHandler).
            // Assume the incoming ByteBuf is a correctly framed message for this protocol.
            // For Teltonika, after IMEI handshake, subsequent messages are AVL data.
            // For other protocols using framers (like GT06/TK103 with DelimiterBasedFrameDecoder),
            // they would also deliver full frames.
            protocolResult = ProtocolDetector.ProtocolDetectionResult.success(detectedProtocol, "DATA", ProtocolDetector.VERSION); // Using a generic "DATA" packet type and common version
            logger.info("BASEPROTOCOLDECODER: Using pre-detected protocol: {}. Packet type: DATA", detectedProtocol);
        } else {
            // Protocol not yet detected (this path should ideally only be taken for the very first packets if ProtocolDetectionHandler isn't handling it)
            protocolResult = protocolDetector.detect(data);
            logger.info("IN BASEPROTOCOLDECODER: Initial detection result: {}", protocolResult);
        }

        logger.info("IN BASEPROTOCOLDECODER: Decoding packet...");
        logger.info("decode(): result passed in is null? {}", (protocolResult == null));
        logger.info("PROTOCOLRESULT IS: {}", protocolResult);

        try {
            if (protocolResult != null && protocolResult.isValid()) {
                ProtocolHandler handler = null;
                switch (protocolResult.getProtocol()) {
                    case "TELTONIKA":
                        handler = teltonikaHandler;
                        break;
                    case "GT06":
                        handler = gt06Handler;
                        break;
                    // Add other cases for different protocols if needed
                    default:
                        logger.warn("No handler found for protocol: {}", protocolResult.getProtocol());
                        ctx.close(); // Close connection if protocol is valid but no handler
                        return;
                }

                if (handler != null && handler.supports(protocolResult.getProtocol())) {
                    // For Teltonika IMEI, the handshake and response are handled by ProtocolDetectionHandler.
                    // If a Teltonika IMEI packet still reaches here, it might indicate a flow issue,
                    // but for framed Teltonika AVL DATA, and other framed protocols, handle them.
                    if ("TELTONIKA".equals(protocolResult.getProtocol()) && "IMEI".equals(protocolResult.getPacketType())) {
                        logger.warn("IMEI packet reached BaseProtocolDecoder. This should be handled by ProtocolDetectionHandler.");
                        // Do not process IMEI again here; let ProtocolDetectionHandler handle it.
                        // If it's already handled, this message might be a duplicate or misrouted.
                        return;
                    } else {
                        // For correctly framed data (like Teltonika AVL data or other delimited frames),
                        // pass the data to the appropriate handler.
                        DeviceMessage deviceMessage = handler.handle(data, ctx); // Pass ctx if handler needs to send responses
                        if (deviceMessage != null) {
                            sessionManager.putMessage(deviceMessage);
                            logger.info("Device message processed and put into session manager for device: {}", deviceMessage.getImei());
                        } else {
                            logger.warn("Handler for protocol {} returned null message.", protocolResult.getProtocol());
                        }
                    }
                } else {
                    logger.warn("No suitable handler found or handler does not support protocol: {} with packet type: {}",
                            protocolResult.getProtocol(), protocolResult.getPacketType());
                    ctx.close();
                }
            } else {
                logger.warn("Unsupported or invalid protocol result: {}", protocolResult != null ? protocolResult.getError() : "null result");
                ctx.close(); // Close connection if protocol is not recognized or invalid
            }
        } finally {
            ReferenceCountUtil.release(msg); // Release the ByteBuf whether handled or not
        }
    }

    // Existing helper methods like generateAckResponse, bytesToHex, generateLoginResponse (if they belong here)
    protected String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // This method seems to be for GT06/TK103 login responses, consider moving to specific handlers if not generic
    protected byte[] generateLoginResponse(String serialNumber) {
        byte[] response = new byte[11];
        // Header
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;

        // Packet length (excluding header and terminator)
        response[2] = 0x05; // Login packet length (5 bytes after length field: Protocol number + Serial number + Status)

        // Protocol number (login)
        response[3] = PROTOCOL_LOGIN;

        // Serial number from device (2 bytes)
        // Assuming serialNumber is a 2-byte hex string or short integer
        if (serialNumber != null && serialNumber.length() >= 2) {
            try {
                int serial = Integer.parseInt(serialNumber.substring(serialNumber.length() - 2), 16);
                response[4] = (byte) (serial >> 8);
                response[5] = (byte) (serial & 0xFF);
            } catch (NumberFormatException e) {
                logger.warn("Invalid serial number format for login response: {}", serialNumber);
                response[4] = 0x00; // Default to 0
                response[5] = 0x00;
            }
        } else {
            response[4] = 0x00; // Default to 0
            response[5] = 0x00;
        }


        // Status (success)
        response[6] = 0x01;

        // Calculate CRC
        // CRC is usually calculated over the packet content *after* the header and length,
        // and *before* the terminator.
        // For GT06/TK103, CRC is usually over data from protocol number to status.
        ByteBuffer crcBuffer = ByteBuffer.wrap(response, 2, 5); // From length field (index 2) to status (index 6)
        int crc = Checksum.crc16(Checksum.CRC16_X25, crcBuffer);

        // Add CRC (big-endian)
        response[7] = (byte) (crc >> 8);
        response[8] = (byte) (crc & 0xFF);

        // Terminator
        response[9] = PROTOCOL_TERMINATOR_1;
        response[10] = PROTOCOL_TERMINATOR_2;

        logger.info("Generated login response for serial {}: {}", serialNumber, bytesToHex(response));
        return response;
    }

    protected byte[] generateAckResponse() {
        byte[] response = new byte[10];

        // Header
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;

        // Packet length (5 bytes after length field)
        response[2] = 0x05;

        // Protocol number (login - this might be wrong for generic ACK, check protocol spec)
        response[3] = PROTOCOL_LOGIN; // Assuming ACK uses LOGIN protocol number for simplicity, verify with protocol spec.

        // Empty serial number (2 bytes)
        response[4] = 0x00;
        response[5] = 0x00;

        // Calculate CRC
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 4); // From length to serial number
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        // Add CRC
        response[6] = (byte) (checksum >> 8);
        response[7] = (byte) (checksum & 0xFF);

        // Terminator
        response[8] = PROTOCOL_TERMINATOR_1;
        response[9] = PROTOCOL_TERMINATOR_2;

        return response;
    }
}