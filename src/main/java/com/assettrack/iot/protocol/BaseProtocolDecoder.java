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
import io.netty.util.AttributeKey;
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

    // Protocol constants
    protected static final byte PROTOCOL_HEADER_1 = 0x78;
    protected static final byte PROTOCOL_HEADER_2 = 0x78;
    protected static final byte PROTOCOL_LOGIN = 0x01;
    protected static final byte PROTOCOL_TERMINATOR_1 = 0x0D;
    protected static final byte PROTOCOL_TERMINATOR_2 = 0x0A;
    private static final AttributeKey<ProtocolDetector.ProtocolDetectionResult> ATTR_DETECTION_RESULT =
            AttributeKey.valueOf("PROTOCOL_DETECTION_RESULT");

    private final ProtocolDetector protocolDetector;
    protected final SessionManager sessionManager;
    protected final TeltonikaHandler teltonikaHandler; // The TeltonikaHandler instance
    protected final Gt06Handler gt06Handler;


    @Autowired
    public BaseProtocolDecoder(
            SessionManager sessionManager,
            ProtocolDetector protocolDetector,
            @Autowired(required = false) TeltonikaHandler teltonikaHandler,
            @Autowired(required = false) Gt06Handler gt06Handler) {
        this.sessionManager = sessionManager;
        this.protocolDetector = protocolDetector;
        this.teltonikaHandler = teltonikaHandler;
        this.gt06Handler = gt06Handler;
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ProtocolDetector.ProtocolDetectionResult) {
            ProtocolDetector.ProtocolDetectionResult result = (ProtocolDetector.ProtocolDetectionResult) msg;
            if (!result.isDetected()) {
                logger.warn("Received undetected protocol result: {}", result.getError());
                return;
            }
            ctx.channel().attr(ATTR_DETECTION_RESULT).set(result);
            return;
        }

        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            ProtocolDetector.ProtocolDetectionResult result = ctx.channel().attr(ATTR_DETECTION_RESULT).get();

            try {
                Object decoded = decode(ctx, buf, result);
                if (decoded != null) {
                    ctx.fireChannelRead(decoded);
                }
            } finally {
                ReferenceCountUtil.release(buf);
            }
        }
    }


    protected abstract DeviceMessage handle(byte[] data) throws ProtocolException; // This is the GT06 handler

    //@Override // This overrides the default `decode` behavior in BaseProtocolDecoder
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buf, ProtocolDetector.ProtocolDetectionResult result) {
        logger.info("Is protocolDetector null? {}", protocolDetector == null);

        try {
            logger.info("IN BASEPROTOCOLDECODER: Decoding packet...");

            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data); // Read data without consuming here, `handle` or `teltonikaHandler` will consume

            logger.info("decode(): result passed in is null? {}", result == null);

            // If no result provided, perform detection (fallback or if result was not passed as separate message)
            if (result == null) {
                logger.debug("No detection result provided, performing detection within BaseProtocolDecoder.");
                result = protocolDetector.detect(data);
            }
            logger.info("PROTOCOLRESULT IS: {}", result);
            logger.info("Forcing protocolDetector.detect(data). Actual class: {}", protocolDetector.getClass().getName());
            //result = protocolDetector.detect(data);

            logger.info("Processing packet with protocol: {}, type: {}",
                    result.getProtocol(), result.getPacketType());

            // --- Route to TeltonikaHandler or GT06 handler ---
            if ("TELTONIKA".equals(result.getProtocol())) {
                // ... existing Teltonika handling ...
                if (teltonikaHandler != null) {
                    logger.info("Delegating Teltonika packet to TeltonikaHandler: Protocol={}, PacketType={}", result.getProtocol(), result.getPacketType());
                    // Use the existing handle method in TeltonikaHandler which correctly processes IMEI/DATA packets
                    DeviceMessage teltonikaMessage = teltonikaHandler.handle(data, ctx); // Pass raw data and context
                    if (teltonikaMessage != null) {
                        enrichMessageWithContext(ctx, teltonikaMessage);
                        return teltonikaMessage;
                    } else {
                        logger.warn("TeltonikaHandler did not return a message for protocol type: {}", result.getPacketType());
                        return null; // TeltonikaHandler couldn't process this packet
                    }
                }
            }
            else if ("GT06".equals(result.getProtocol())) {
                // 1) Try Gt06Handler first if available
                if (gt06Handler != null) {
                    logger.info("Delegating GT06 packet to Gt06Handler: Protocol={}, PacketType={}", result.getProtocol(), result.getPacketType());
                    DeviceMessage msg = gt06Handler.handle(data, ctx);
                    if (msg != null) {
                        enrichMessageWithContext(ctx, msg);
                        return msg;
                    }
                    logger.info("Gt06Handler returned null, falling back");
                }

                // 2) Fallback to default GT06 handling
                try {
                    DeviceMessage fallback = handle(data);
                    if (fallback != null) {
                        enrichMessageWithContext(ctx, fallback);
                        return fallback;
                    }
                } catch (ProtocolException e) {
                    logger.error("GT06 handling error", e);
                }

                return null;
            }

            logger.warn("Unsupported protocol: {}", result.getProtocol());
            return null;
        } catch (Exception e) {
            logger.error("Decoding error in BaseProtocolDecoder: {}", e.getMessage(), e);
            // Don't re-throw, just log and return null so pipeline can continue
            return null;
        }
    }

    boolean isValidGT06Header(byte[] data) {
        return data.length >= 2 &&
                data[0] == PROTOCOL_HEADER_1 &&
                data[1] == PROTOCOL_HEADER_2;
    }

    void enrichMessageWithContext(ChannelHandlerContext ctx, DeviceMessage message) {
        if (message.getProtocol() == null) {
            // Default to GT06 if protocol is not set by the specific handler
            message.setProtocol("GT06");
        }

        if (ctx.channel() instanceof SocketChannel) {
            message.setChannel((SocketChannel) ctx.channel());
        }
        message.setRemoteAddress(ctx.channel().remoteAddress());

        if (message.getImei() != null) {
            long deviceId = generateDeviceId(message.getImei());
            message.addParsedData("deviceId", deviceId);
            logger.info("Generated device ID {} for IMEI {}", deviceId, message.getImei());
        }
    }

    protected long generateDeviceId(String imei) {
        return imei != null ? imei.hashCode() & 0xffffffffL : 0L;
    }

    protected String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    protected String extractImei(byte[] imeiBytes) throws ProtocolException {
        if (imeiBytes == null || imeiBytes.length != 8) {
            throw new ProtocolException("Invalid IMEI bytes length");
        }

        StringBuilder imei = new StringBuilder(16);
        for (byte b : imeiBytes) {
            imei.append(String.format("%02X", b));
        }

        // Remove leading zeros while maintaining 15 digits
        while (imei.length() > 15 && imei.charAt(0) == '0') {
            imei.deleteCharAt(0);
        }

        if (imei.length() != 15) {
            throw new ProtocolException("Invalid IMEI length: " + imei.length());
        }

        logger.info("Extracted IMEI: {}", imei);
        return imei.toString();
    }

    protected Position parseGpsData(ByteBuffer buffer) {
        Position position = new Position();

        // Parse timestamp (6 bytes: YY MM DD HH mm ss)
        position.setTimestamp(LocalDateTime.of(
                2000 + (buffer.get() & 0xFF),  // Year
                buffer.get() & 0xFF,            // Month
                buffer.get() & 0xFF,            // Day
                buffer.get() & 0xFF,            // Hour
                buffer.get() & 0xFF,            // Minute
                buffer.get() & 0xFF             // Second
        ));

        position.setSatellites(buffer.get() & 0xFF);
        position.setLatitude(buffer.getInt() / 1800000.0);
        position.setLongitude(buffer.getInt() / 1800000.0);
        position.setSpeed((buffer.get() & 0xFF) * 1.852);  // Convert knots to km/h
        position.setCourse((double) (buffer.getShort() & 0xFFFF));

        logger.debug("Parsed GPS position: {}", position);
        return position;
    }

    protected byte[] generateLoginResponse(short serialNumber) {
        byte[] response = new byte[11];

        // Header
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;

        // Packet length (5 bytes: protocol + serial + status)
        response[2] = 0x05;

        // Protocol number (login)
        response[3] = PROTOCOL_LOGIN;

        // Serial number (big-endian)
        response[4] = (byte) (serialNumber >> 8);
        response[5] = (byte) (serialNumber & 0xFF);

        // Status (success)
        response[6] = 0x01;

        // Calculate CRC
        ByteBuffer crcBuffer = ByteBuffer.wrap(response, 2, 5);
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

        // Packet length (5 bytes)
        response[2] = 0x05;

        // Protocol number (login)
        response[3] = PROTOCOL_LOGIN;

        // Empty serial number
        response[4] = 0x00;
        response[5] = 0x00;

        // Calculate CRC
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 4);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        // Add CRC
        response[6] = (byte) (checksum >> 8);
        response[7] = (byte) (checksum & 0xFF);

        // Terminator
        response[8] = PROTOCOL_TERMINATOR_1;
        response[9] = PROTOCOL_TERMINATOR_2;

        logger.info("Generated ACK response: {}", bytesToHex(response));
        return response;
    }
}