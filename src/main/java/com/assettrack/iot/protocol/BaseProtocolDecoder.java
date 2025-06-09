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

    protected final ProtocolDetector protocolDetector;
    protected final SessionManager sessionManager;
    protected final TeltonikaHandler teltonikaHandler; // The TeltonikaHandler instance


    @Autowired
    public BaseProtocolDecoder(SessionManager sessionManager, ProtocolDetector protocolDetector, @Autowired(required = false) TeltonikaHandler teltonikaHandler) {
        this.sessionManager = sessionManager;
        this.protocolDetector = protocolDetector;
        this.teltonikaHandler = teltonikaHandler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        logger.debug("BaseProtocolDecoder received message of type: {}", msg.getClass().getSimpleName());

        // ProtocolDetectionResult might arrive before the ByteBuf or intermingled.
        // We need to ensure we have both to make a decision.
        // A common pattern is to store the result in ChannelHandlerContext's attributes
        // or ensure `ProtocolDetectionHandler` fires them as a single custom aggregated message.
        // For simplicity here, we'll try to get both from the pipeline.

        ProtocolDetector.ProtocolDetectionResult result = null;
        ByteBuf buf = null;

        // Try to get the ProtocolDetectionResult from the current message if it's there.
        // This scenario handles `ctx.fireChannelRead(result)` followed by `ctx.fireChannelRead(buf)`
        // from ProtocolDetectionHandler.
        if (msg instanceof ProtocolDetector.ProtocolDetectionResult) {
            result = (ProtocolDetector.ProtocolDetectionResult) msg;
            ctx.channel().attr(ATTR_DETECTION_RESULT).set((ProtocolDetector.ProtocolDetectionResult) msg);
            // Store the result temporarily, or expect the ByteBuf next.
            // For a robust solution, consider Netty's `MessageToMessageDecoder` or a custom aggregator.
            // For this setup, we'll proceed assuming result and buf arrive sequentially or are handled by `decode`'s fallback.
            ctx.fireChannelRead(msg); // Pass the result along, as `decode` might need it too.
            return; // Wait for the ByteBuf
        } else if (msg instanceof ByteBuf) {
            buf = (ByteBuf) msg;
            result = ctx.channel().attr(ATTR_DETECTION_RESULT).get();
            // Attempt to retrieve a result if it was fired just before this ByteBuf
            // (This requires careful pipeline design or an aggregator)
            // For now, the `decode` method will handle re-detection if result is null.
        } else {
            // Unknown message type, pass it on
            ctx.fireChannelRead(msg);
            return;
        }

        if (buf != null && buf.isReadable()) {
            // Retain the buffer so it can be safely used by `decode` method after reading bytes.
            // `decode` method will consume and release it.
            buf.retain();
            try {
                // Pass null for result initially if not directly available; decode will re-detect.
                // A better approach would be to ensure result is available here, e.g., via aggregator or attribute.
                Object decodedMessage = decode(ctx, buf, result); // Pass null for result, decode will get it or re-detect

                if (decodedMessage != null) {
                    ctx.fireChannelRead(decodedMessage);
                    logger.info("Successfully decoded message of type: {}",
                            decodedMessage instanceof DeviceMessage ?
                                    ((DeviceMessage) decodedMessage).getMessageType() : "Unknown");
                } else {
                    logger.warn("No message decoded from raw data: {}", bytesToHex(new byte[buf.readableBytes()])); // Log the data before release
                }
            } catch (Exception e) {
                logger.error("Error in protocol decoding: {}", e.getMessage(), e);
                ctx.close();
            } finally {
                ReferenceCountUtil.release(buf); // Ensure the ByteBuf is released after processing
            }
        } else if (buf != null) {
            // If buffer is empty or not readable, release it
            ReferenceCountUtil.release(buf);
        }
    }


    protected abstract DeviceMessage handle(byte[] data) throws ProtocolException; // This is the GT06 handler

    //@Override // This overrides the default `decode` behavior in BaseProtocolDecoder
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buf, ProtocolDetector.ProtocolDetectionResult result) {
        try {
            logger.info("IN BASEPROTOCOLDECODER: Decoding packet...");

            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data); // Read data without consuming here, `handle` or `teltonikaHandler` will consume

            // If no result provided, perform detection (fallback or if result was not passed as separate message)
            if (result == null) {
                logger.debug("No detection result provided, performing detection within BaseProtocolDecoder.");
                result = protocolDetector.detect(data);
            }
            logger.info("PROTOCOLRESULT IS: {}", result);
            // --- Route to TeltonikaHandler or GT06 handler ---
            if (result != null && "TELTONIKA".equals(result.getProtocol())) {
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
                } else {
                    logger.error("TeltonikaHandler is not available, but Teltonika protocol detected. Cannot process.");
                    return null;
                }
            } else {
                // If not Teltonika, assume it's GT06 or other protocols handled by `GenericProtocolDecoder`
                logger.info("Processing as non-Teltonika packet (likely GT06): Protocol={}, PacketType={}",
                        result != null ? result.getProtocol() : "UNKNOWN",
                        result != null ? result.getPacketType() : "UNKNOWN");

                // Manual GT06 header detection as a final fallback if ProtocolDetector didn't identify it or identified as UNKNOWN
                if (result == null && isValidGT06Header(data)) {
                    result = ProtocolDetector.ProtocolDetectionResult.success("GT06", "UNKNOWN_FROM_HEADER", "1.0");
                    logger.info("Manually re-classified packet as GT06 based on header.");
                }

                if (result != null && "GT06".equals(result.getProtocol())) {
                    // Call the abstract `handle` method, which `GenericProtocolDecoder` implements for GT06
                    DeviceMessage gt06Message = handle(data); // This is where GenericProtocolDecoder's GT06 logic runs
                    if (gt06Message != null) {
                        enrichMessageWithContext(ctx, gt06Message);
                        logger.info("Decoded GT06 message for IMEI: {}", gt06Message.getImei());
                    }
                    return gt06Message;
                } else {
                    logger.debug("Packet not identified as Teltonika or GT06. Returning null.");
                    return null; // Cannot decode this packet
                }
            }
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