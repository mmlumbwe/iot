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

                    // Let the child class handle the decoding
                    Object result = decode(ctx, buf, protocolDetector.detect(data));
                    if (result != null) {
                        ctx.fireChannelRead(result);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error in protocol decoding", e);
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
                if (data.length >= 2 && data[0] == 0x78 && data[1] == 0x78) {
                    result = ProtocolDetector.ProtocolDetectionResult.success("GT06", "LOGIN", "1.0");
                    logger.info("Manually detected GT06 packet");
                } else {
                    return null;
                }
            }

            DeviceMessage message = handle(data);

            if (message != null) {
                message.setProtocolType("GT06");
                if (ctx.channel() instanceof SocketChannel) {
                    message.setChannel((SocketChannel) ctx.channel());
                }
                message.setRemoteAddress(ctx.channel().remoteAddress());

                if (message.getImei() != null) {
                    message.addParsedData("deviceId", generateDeviceId(message.getImei()));
                }
            }
            return message;
        } catch (Exception e) {
            logger.error("Decoding error", e);
            return null;
        }
    }

    protected long generateDeviceId(String imei) {
        return imei != null ? imei.hashCode() & 0xffffffffL : 0L;
    }

    protected String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    protected String extractImei(byte[] imeiBytes) {
        StringBuilder imei = new StringBuilder();
        for (byte b : imeiBytes) {
            imei.append(String.format("%02X", b));
        }
        while (imei.length() > 15 && imei.charAt(0) == '0') {
            imei.deleteCharAt(0);
        }
        return imei.toString();
    }

    protected Position parseGpsData(ByteBuffer buffer) {
        Position position = new Position();

        position.setTimestamp(LocalDateTime.of(
                2000 + (buffer.get() & 0xFF),
                buffer.get() & 0xFF,
                buffer.get() & 0xFF,
                buffer.get() & 0xFF,
                buffer.get() & 0xFF,
                buffer.get() & 0xFF
        ));

        position.setSatellites(buffer.get() & 0xFF);
        position.setLatitude(buffer.getInt() / 1800000.0);
        position.setLongitude(buffer.getInt() / 1800000.0);
        position.setSpeed((buffer.get() & 0xFF) * 1.852);

        int course = buffer.getShort() & 0xFFFF;
        position.setCourse((double) course);

        return position;
    }

    protected byte[] generateLoginResponse(short serialNumber) {
        byte[] response = new byte[11];

        // Start bits
        response[0] = 0x78;
        response[1] = 0x78;

        // Packet length: 5 bytes (protocol + serial number + CRC)
        response[2] = 0x00;
        response[3] = 0x05;

        // Protocol number: 0x01 for login
        response[4] = 0x01;

        // Serial number (2 bytes, big-endian)
        response[5] = (byte) (serialNumber >> 8);
        response[6] = (byte) (serialNumber & 0xFF);

        // Calculate CRC over bytes [4] to [6] (inclusive)
        byte[] crcInput = new byte[]{response[4], response[5], response[6]};
        int crc = Checksum.crc16(Checksum.CRC16_X25, ByteBuffer.wrap(crcInput));

        // Insert CRC (big-endian)
        response[7] = (byte) (crc >> 8);
        response[8] = (byte) (crc & 0xFF);

        // End bits
        response[9] = 0x0D;
        response[10] = 0x0A;

        logger.info("Generated login response: {}", bytesToHex(response));
        return response;
    }

    protected byte[] generateAckResponse() {
        byte[] response = new byte[11];

        response[0] = (byte) 0x78;
        response[1] = (byte) 0x78;
        response[2] = 0x05;
        response[3] = 0x01;
        response[4] = 0x00;
        response[5] = 0x00;

        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 4);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[6] = (byte) (checksum >> 8);
        response[7] = (byte) (checksum & 0xFF);

        response[8] = 0x0D;
        response[9] = 0x0A;

        logger.debug("Generated ACK response: {}", bytesToHex(response));
        return response;
    }
}