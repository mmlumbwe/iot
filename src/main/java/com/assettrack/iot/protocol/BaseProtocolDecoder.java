package com.assettrack.iot.protocol;

import com.assettrack.iot.config.Checksum;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.session.SessionManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.ReferenceCountUtil;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    protected final ProtocolDetector protocolDetector;
    protected final SessionManager sessionManager;

    @Autowired
    public BaseProtocolDecoder(SessionManager sessionManager, ProtocolDetector protocolDetector) {
        this.sessionManager = sessionManager;
        this.protocolDetector = protocolDetector;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                if (buf.isReadable()) {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.getBytes(buf.readerIndex(), data);
                    logger.info("Received raw data: {}", bytesToHex(data));

                    ProtocolDetector.ProtocolDetectionResult result = protocolDetector.detect(data);
                    Object decodedMessage = decode(ctx, buf, result);

                    if (decodedMessage != null) {
                        ctx.fireChannelRead(decodedMessage);
                        logger.info("Successfully decoded message of type: {}",
                                decodedMessage instanceof DeviceMessage ?
                                        ((DeviceMessage) decodedMessage).getMessageType() : "Unknown");
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error in protocol decoding: {}", e.getMessage(), e);
            ctx.close();
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    protected abstract DeviceMessage handle(byte[] data) throws ProtocolException;

    protected Object decode(ChannelHandlerContext ctx,
                            ByteBuf buf,
                            ProtocolDetector.ProtocolDetectionResult result) {
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);

            // Fallback detection if initial detection failed
            if (result == null || !"GT06".equals(result.getProtocol())) {
                if (isValidGT06Header(data)) {
                    result = ProtocolDetector.ProtocolDetectionResult.success("GT06", "LOGIN", "1.0");
                    logger.info("Manually detected GT06 packet");
                } else {
                    logger.debug("Packet doesn't match GT06 protocol");
                    return null;
                }
            }

            DeviceMessage message = handle(data);
            if (message != null) {
                enrichMessageWithContext(ctx, message);
                logger.debug("Decoded message for IMEI: {}", message.getImei());
            }
            return message;
        } catch (Exception e) {
            logger.error("Decoding error for packet: {}", e.getMessage(), e);
            return null;
        }
    }

    private boolean isValidGT06Header(byte[] data) {
        return data.length >= 2 &&
                data[0] == PROTOCOL_HEADER_1 &&
                data[1] == PROTOCOL_HEADER_2;
    }

    private void enrichMessageWithContext(ChannelHandlerContext ctx, DeviceMessage message) {
        message.setProtocolType("GT06");
        if (ctx.channel() instanceof SocketChannel) {
            message.setChannel((SocketChannel) ctx.channel());
        }
        message.setRemoteAddress(ctx.channel().remoteAddress());

        if (message.getImei() != null) {
            long deviceId = generateDeviceId(message.getImei());
            message.addParsedData("deviceId", deviceId);
            logger.debug("Generated device ID {} for IMEI {}", deviceId, message.getImei());
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

        logger.debug("Extracted IMEI: {}", imei);
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

        logger.debug("Generated ACK response: {}", bytesToHex(response));
        return response;
    }
}