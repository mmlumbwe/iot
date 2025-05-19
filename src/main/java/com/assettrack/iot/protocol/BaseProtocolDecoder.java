package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.session.SessionManager;
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
                // Handle protocol-specific decoding
                ProtocolDetector.ProtocolDetectionResult result = (ProtocolDetector.ProtocolDetectionResult) msg;
                if ("GT06".equals(result.getProtocol())) {
                    // GT06-specific decoding logic
                    decodeGt06Message(ctx, result);
                }
            } else if (msg instanceof ByteBuf) {
                // Fallback processing if no protocol detected
                ByteBuf buf = (ByteBuf) msg;
                if (buf.isReadable()) {
                    // Attempt to process as GT06 anyway (backward compatibility)
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

    /**
     * Abstract method to be implemented by protocol-specific decoders
     * @param data The raw byte data to handle
     * @return Processed DeviceMessage
     * @throws ProtocolException if protocol parsing fails
     */
    protected abstract DeviceMessage handle(byte[] data) throws ProtocolException;

    //@Override
    protected Object decode(ChannelHandlerContext ctx,
                            ByteBuf buf,
                            ProtocolDetector.ProtocolDetectionResult result) {
        try {
            if (result == null || !"GT06".equals(result.getProtocol())) {
                return null;
            }

            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);

            // Fallback detection for GT06
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

    /**
     * Checks if the data could be a GT06 protocol packet
     */
    private boolean isPotentialGt06Packet(byte[] data) {
        // Minimum GT06 packet is 12 bytes and starts with 0x78 0x78
        return data != null &&
                data.length >= 12 &&
                data[0] == 0x78 &&
                data[1] == 0x78;
    }

    /**
     * Decodes a GT06 message from protocol detection result
     */
    private void decodeGt06Message(ChannelHandlerContext ctx, ProtocolDetector.ProtocolDetectionResult result) {
        try {
            // Extract the raw data from the result's metadata if stored
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

    /**
     * Decodes a GT06 message from raw bytes
     */
    private void decodeGt06Message(ChannelHandlerContext ctx, byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Skip header (0x78 0x78)
            buffer.position(2);

            int length = buffer.get() & 0xFF;
            byte protocol = buffer.get();

            logger.debug("Decoding GT06 packet - Type: 0x{}, Length: {}",
                    String.format("%02X", protocol), length);

            DeviceMessage message = new DeviceMessage();
            message.setProtocolType("GT06");
            Map<String, Object> parsedData = new HashMap<>();

            switch (protocol) {
                case 0x01: // Login
                    handleLoginPacket(buffer, message, parsedData);
                    break;
                case 0x12: // GPS
                    handleGpsPacket(buffer, message, parsedData);
                    break;
                case 0x13: // Heartbeat
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

    /**
     * Handles GT06 login packets
     */
    private void handleLoginPacket(ByteBuffer buffer, DeviceMessage message,
                                   Map<String, Object> parsedData) {
        // IMEI (8 bytes in packed BCD format)
        byte[] imeiBytes = new byte[8];
        buffer.get(imeiBytes);
        String imei = extractImei(imeiBytes);

        // Serial number (2 bytes)
        short serialNumber = buffer.getShort();

        message.setImei(imei);
        message.setMessageType("LOGIN");
        parsedData.put("serialNumber", serialNumber);

        // Generate login response
        byte[] response = generateLoginResponse(serialNumber);
        message.setResponseData(response);
        message.setResponseRequired(true);
    }

    /**
     * Handles GT06 GPS packets
     */
    private void handleGpsPacket(ByteBuffer buffer, DeviceMessage message,
                                 Map<String, Object> parsedData) {
        // Parse GPS data
        Position position = parseGpsData(buffer);
        parsedData.put("position", position);

        message.setMessageType("GPS");
        message.setResponseData(generateAckResponse());
    }

    /**
     * Extracts IMEI from packed BCD format
     */
    private String extractImei(byte[] imeiBytes) {
        StringBuilder imei = new StringBuilder();
        for (byte b : imeiBytes) {
            imei.append(String.format("%02X", b));
        }
        // Remove leading zeros if necessary
        while (imei.length() > 15 && imei.charAt(0) == '0') {
            imei.deleteCharAt(0);
        }
        return imei.toString();
    }

    /**
     * Parses GT06 GPS data
     */
    private Position parseGpsData(ByteBuffer buffer) {
        Position position = new Position();

        // Date and time (6 bytes)
        position.setTimestamp(LocalDateTime.of(
                2000 + (buffer.get() & 0xFF), // Year
                buffer.get() & 0xFF,          // Month
                buffer.get() & 0xFF,          // Day
                buffer.get() & 0xFF,          // Hour
                buffer.get() & 0xFF,          // Minute
                buffer.get() & 0xFF           // Second
        ));

        // GPS info
        position.setSatellites(buffer.get() & 0xFF);
        position.setLatitude(buffer.getInt() / 1800000.0);
        position.setLongitude(buffer.getInt() / 1800000.0);
        position.setSpeed((buffer.get() & 0xFF) * 1.852); // Knots to km/h

        // Course and status flags
        int course = buffer.getShort() & 0xFFFF;
        position.setCourse((double) course);

        return position;
    }

    /**
     * Handles GT06 heartbeat packets
     */
    private void handleHeartbeatPacket(ByteBuffer buffer, DeviceMessage message,
                                       Map<String, Object> parsedData) {
        // Typically heartbeat packets are empty after the protocol byte
        message.setMessageType("HEARTBEAT");

        // Some devices include voltage info in heartbeat (check buffer remaining)
        if (buffer.remaining() >= 2) {
            int voltageLevel = buffer.get() & 0xFF; // Often 1 byte for voltage
            parsedData.put("battery", voltageLevel);
        }

        // Generate ACK response
        message.setResponseData(generateAckResponse());
        message.setResponseRequired(true);
    }

    /**
     * Generates a login response packet (0x01)
     * @param serialNumber The serial number from the login packet
     * @return Byte array with the response
     */
    private byte[] generateLoginResponse(short serialNumber) {
        ByteBuffer buf = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);

        // Header
        buf.put((byte) 0x78);
        buf.put((byte) 0x78);

        // Length (excluding header and checksum)
        buf.put((byte) 0x05);

        // Protocol number (0x01 for login response)
        buf.put((byte) 0x01);

        // Serial number (echo from login)
        buf.putShort(serialNumber);

        // Checksum (simple sum of bytes after header)
        byte checksum = calculateChecksum(buf.array(), 2, 3);
        buf.put(checksum);

        // Terminator
        buf.put((byte) 0x0D);
        buf.put((byte) 0x0A);

        return buf.array();
    }

    private byte calculateChecksum(byte[] data, int start, int length) {
        byte sum = 0;
        for (int i = start; i < start + length; i++) {
            sum += data[i];
        }
        return sum;
    }

    /**
     * Generates a standard acknowledgment packet (0x01 type)
     * @return Byte array with the ACK response
     */
    private byte[] generateAckResponse() {
        ByteBuffer buf = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);

        // Header
        buf.put((byte) 0x78);
        buf.put((byte) 0x78);

        // Length (fixed for ACK)
        buf.put((byte) 0x05);

        // Protocol number (0x01 for ACK)
        buf.put((byte) 0x01);

        // Serial number (0 for ACK)
        buf.putShort((short) 0);

        // Checksum
        byte checksum = calculateChecksum(buf.array(), 2, 3);
        buf.put(checksum);

        // Terminator
        buf.put((byte) 0x0D);
        buf.put((byte) 0x0A);

        return buf.array();
    }
}