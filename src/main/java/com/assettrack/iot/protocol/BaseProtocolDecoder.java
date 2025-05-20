package com.assettrack.iot.protocol;

import com.assettrack.iot.config.Checksum;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.session.DeviceSession;
import com.assettrack.iot.session.SessionManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import io.netty.channel.socket.SocketChannel;
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
            if (msg instanceof ProtocolDetector.ProtocolDetectionResult) {
                ProtocolDetector.ProtocolDetectionResult result = (ProtocolDetector.ProtocolDetectionResult) msg;
                if ("GT06".equals(result.getProtocol())) {
                    decodeGt06Message(ctx, result);
                }
            } else if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                if (buf.isReadable()) {
                    byte[] data = new byte[buf.readableBytes()];
                    buf.getBytes(buf.readerIndex(), data);
                    if (isPotentialGt06Packet(data)) {
                        decodeGt06Message(ctx, data);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Decoding error", e);
        } finally {
            if (msg instanceof ByteBuf) {
                ((ByteBuf) msg).release();
            }
        }
    }

    protected abstract DeviceMessage handle(byte[] data) throws ProtocolException;

    protected Object decode(ChannelHandlerContext ctx,
                            ByteBuf buf,
                            ProtocolDetector.ProtocolDetectionResult result) {
        try {
            if (result == null || !"GT06".equals(result.getProtocol())) {
                return null;
            }

            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);

            if ((result == null || !"GT06".equals(result.getProtocol()))) {
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
        } finally {
            if (buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    protected long generateDeviceId(String imei) {
        return imei != null ? imei.hashCode() & 0xffffffffL : 0L;
    }

    private void logDetectionResult(ProtocolDetector.ProtocolDetectionResult result, byte[] data) {
        if (logger.isDebugEnabled()) {
            logger.debug("Detected protocol: {}, Type: {}, Version: {}",
                    result.getProtocol(), result.getPacketType(), result.getVersion());
            logger.debug("Raw data ({} bytes): {}", data.length, bytesToHex(data));
        }
        if (result == null) {
            logger.warn("Protocol detection failed for data: {}", bytesToHex(data));
        } else {
            logger.info("Detected protocol: {}", result.getProtocol());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private boolean isPotentialGt06Packet(byte[] data) {
        return data != null &&
                data.length >= 12 &&
                data[0] == 0x78 &&
                data[1] == 0x78;
    }

    private void decodeGt06Message(ChannelHandlerContext ctx, ProtocolDetector.ProtocolDetectionResult result) {
        try {
            byte[] data = (byte[]) result.getMetadata().get("rawData");
            if (data == null) {
                logger.warn("No raw data in protocol detection result");
                return;
            }
            decodeGt06Message(ctx, data);
        } catch (Exception e) {
            logger.error("GT06 decoding error", e);
        }
    }

    private void decodeGt06Message(ChannelHandlerContext ctx, byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            buffer.position(2);

            int length = buffer.get() & 0xFF;
            byte protocol = buffer.get();

            logger.debug("Decoding GT06 packet - Type: 0x{}, Length: {}",
                    String.format("%02X", protocol), length);

            DeviceMessage message = new DeviceMessage();
            message.setProtocolType("GT06");
            Map<String, Object> parsedData = new HashMap<>();

            switch (protocol) {
                case 0x01:
                    handleLoginPacket(buffer, message, parsedData);
                    break;
                case 0x12:
                    handleGpsPacket(buffer, message, parsedData);
                    break;
                case 0x13:
                    handleHeartbeatPacket(buffer, message, parsedData);
                    break;
                default:
                    logger.warn("Unsupported GT06 protocol type: 0x{}",
                            String.format("%02X", protocol));
                    return;
            }

            message.setParsedData(parsedData);
            ctx.fireChannelRead(message);

        } catch (Exception e) {
            logger.error("GT06 message decoding failed", e);
        }
    }

    private void handleLoginPacket(ByteBuffer buffer, DeviceMessage message,
                                   Map<String, Object> parsedData) {
        byte[] imeiBytes = new byte[8];
        buffer.get(imeiBytes);
        String imei = extractImei(imeiBytes);
        short serialNumber = buffer.getShort();

        // Check for duplicate serial numbers
        DeviceSession session = sessionManager.getSessionByImei(imei);
        if (session != null && session.isDuplicateSerialNumber(serialNumber)) {
            message.setDuplicate(true);
            logger.warn("Duplicate login packet from IMEI: {}, Serial: {}", imei, serialNumber);
            return;
        }

        message.setImei(imei);
        message.setMessageType("LOGIN");
        parsedData.put("serialNumber", serialNumber);

        // Only generate response if not duplicate
        if (!message.isDuplicate()) {
            byte[] response = generateLoginResponse(serialNumber);
            message.setResponseData(response);
            message.setResponseRequired(true);
        }
    }

    private void handleGpsPacket(ByteBuffer buffer, DeviceMessage message,
                                 Map<String, Object> parsedData) {
        Position position = parseGpsData(buffer);
        parsedData.put("position", position);

        message.setMessageType("GPS");
        message.setResponseData(generateAckResponse());
    }

    private String extractImei(byte[] imeiBytes) {
        StringBuilder imei = new StringBuilder();
        for (byte b : imeiBytes) {
            imei.append(String.format("%02X", b));
        }
        while (imei.length() > 15 && imei.charAt(0) == '0') {
            imei.deleteCharAt(0);
        }
        return imei.toString();
    }

    private Position parseGpsData(ByteBuffer buffer) {
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

    private void handleHeartbeatPacket(ByteBuffer buffer, DeviceMessage message,
                                       Map<String, Object> parsedData) {
        message.setMessageType("HEARTBEAT");

        if (buffer.remaining() >= 2) {
            int voltageLevel = buffer.get() & 0xFF;
            parsedData.put("battery", voltageLevel);
        }

        message.setResponseData(generateAckResponse());
        message.setResponseRequired(true);
    }

    public byte[] generateLoginResponse(short serialNumber) {
        byte[] response = new byte[11];

        response[0] = 0x78;
        response[1] = 0x78;
        response[2] = 0x05;
        response[3] = 0x01;
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber & 0xFF);
        response[6] = 0x01;

        int checksum = 0;
        for (int i = 2; i <= 6; i++) {
            checksum ^= response[i] & 0xFF;
        }
        response[7] = (byte)(checksum >> 8);
        response[8] = (byte)(checksum & 0xFF);

        response[9] = 0x0D;
        response[10] = 0x0A;

        logger.info("Generated XOR response: {}", bytesToHex(response));
        return response;
    }

    private byte[] generateAckResponse() {
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