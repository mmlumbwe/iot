package com.assettrack.iot.protocol;

import com.assettrack.iot.config.Checksum;
import com.assettrack.iot.handler.network.AcknowledgementHandler;
import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.session.DeviceSession;
import com.assettrack.iot.session.SessionManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.codec.binary.Hex;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ChannelHandler.Sharable
public class Gt06Handler extends BaseProtocolDecoder implements ProtocolHandler {
    private static final Logger logger = LoggerFactory.getLogger(Gt06Handler.class);

    // Protocol constants
    private static final byte PROTOCOL_HEADER_1 = 0x78;
    private static final byte PROTOCOL_HEADER_2 = 0x78;
    private static final byte PROTOCOL_GPS = 0x12;
    private static final byte PROTOCOL_LOGIN = 0x01;
    private static final byte PROTOCOL_HEARTBEAT = 0x13;
    private static final byte PROTOCOL_ALARM = 0x16;
    private static final byte PROTOCOL_ERROR = 0x7F;
    private static final byte PROTOCOL_FOOTER = 0x0A;

    private static final int MIN_PACKET_LENGTH = 12;
    private static final int LOGIN_PACKET_LENGTH = 22;
    private static final long SESSION_TIMEOUT_MS = 300000; // 5 minutes

    private final AtomicReference<String> lastValidImei = new AtomicReference<>();
    private final Map<String, DeviceSession> activeSessions = new ConcurrentHashMap<>();

    // VL03-specific constants
    private static final byte VL03_PROTOCOL_EXTENDED = 0x26;
    private static final byte VL03_ALARM_TYPE = (byte) 0xA2;

    @Autowired
    private AcknowledgementHandler acknowledgementHandler;

    @Autowired
    public Gt06Handler(SessionManager sessionManager,
                       ProtocolDetector protocolDetector,
                       AcknowledgementHandler acknowledgementHandler) {
        super(sessionManager, protocolDetector);
        this.acknowledgementHandler = acknowledgementHandler;
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buf,
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

            DeviceMessage message = handle(data, ctx);
            if (message != null && message.getImei() != null) {
                message.addParsedData("deviceId", generateDeviceId(message.getImei()));
            }
            return message;
        } catch (Exception e) {
            logger.error("Decoding error for packet: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        return handle(data, null);
    }

    @Override
    public DeviceMessage handle(byte[] data, ChannelHandlerContext ctx) throws ProtocolException {
        logger.debug("Processing GT06 packet: {}", Hex.encodeHexString(data));
        DeviceMessage message = new DeviceMessage();
        message.setProtocolType("GT06");
        Map<String, Object> parsedData = new HashMap<>();
        message.setParsedData(parsedData);

        try {
            logger.info("Raw input packet ({} bytes): {}", data.length, bytesToHex(data));
            validatePacket(data);

            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            buffer.position(2); // Skip header
            int length = buffer.get() & 0xFF;
            byte protocol = buffer.get();

            logger.info("Detected GT06 packet - Protocol: 0x{}, Length: {}",
                    String.format("%02X", protocol), length);

            Variant variant = detectVariant(buffer);
            logger.debug("Detected device variant: {}", variant);

            switch (protocol) {
                case 0x01: // Login
                    return handleLogin(buffer, message, parsedData, variant, ctx);
                case 0x13: // GPS short
                    return handleGpsShort(buffer, message, parsedData);
                case (byte) 0x8A: // Heartbeat/config response
                    return handleHeartbeat(buffer, message, parsedData);
                case (byte) 0xA0: // GPS extended
                    return handleGpsExtended(buffer, message, parsedData);
                default:
                    throw new ProtocolException("Unsupported GT06 protocol type: " + protocol);
            }
        } catch (Exception e) {
            logger.error("Error processing packet: {}", Hex.encodeHexString(data), e);
            message.setError(e.getMessage());
            message.setResponseData(generateErrorResponse(e));
            message.setResponseRequired(true);
            return message;
        }
    }


    /**********NEW***********/
    /*private DeviceMessage handleLogin(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData, Variant variant, ChannelHandlerContext ctx) {
        byte[] imeiBytes = new byte[8];
        buffer.get(imeiBytes);
        String imei = extractImei(imeiBytes);
        short serial = buffer.getShort();
        parsedData.put("serialNumber", serial);
        message.setImei(imei);
        message.setMessageType("LOGIN");
        message.setSerialNumber(serial);
        message.setResponseRequired(true);
        message.setResponseData(generateLoginResponse(variant, serial, (byte) 0x01));
        return message;
    }*/

    private DeviceMessage handleGpsShort(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData) {
        buffer.get(); // Skip data type
        int rawLat = buffer.getInt();
        int rawLng = buffer.getInt();
        double latitude = rawLat / 1800000.0;
        double longitude = rawLng / 1800000.0;
        //message.setLatitude(latitude);
        //message.setLongitude(longitude);
        message.setMessageType("GPS");
        return message;
    }

    private DeviceMessage handleHeartbeat(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData) {
        short serial = buffer.getShort();
        parsedData.put("serialNumber", serial);
        message.setMessageType("HEARTBEAT");
        message.setResponseRequired(true);
        message.setResponseData(generateHeartbeatResponse(serial));
        return message;
    }

    private DeviceMessage handleGpsExtended(ByteBuffer buffer, DeviceMessage message, Map<String, Object> parsedData) {
        byte[] gpsData = new byte[28];
        buffer.get(gpsData);
        parsedData.put("gpsExtended", Hex.encodeHexString(gpsData));
        message.setMessageType("GPS_EXTENDED");
        return message;
    }

    private byte[] generateLoginResponse(Variant variant, short serial, byte extension) {
        byte[] response = new byte[] { 0x78, 0x78, 0x05, 0x01,
                (byte)(serial >> 8), (byte)(serial & 0xFF), 0x01, 0x00, 0x00, 0x0D, 0x0A };
        return response;
    }

    private byte[] generateHeartbeatResponse(short serial) {
        byte[] response = new byte[] { 0x78, 0x78, 0x05, 0x13,
                (byte)(serial >> 8), (byte)(serial & 0xFF), 0x00, 0x00, 0x00, 0x0D, 0x0A };
        return response;
    }
    /**********NEW***********/

    private DeviceMessage handleLogin(ByteBuffer buffer, DeviceMessage message,
                                      Map<String, Object> parsedData, Variant variant,
                                      ChannelHandlerContext ctx) throws Exception {
        // Read IMEI (8 bytes in packed BCD format)
        byte[] imeiBytes = new byte[8];
        buffer.get(imeiBytes);

        String imei = extractImei(imeiBytes);
        lastValidImei.set(imei);

        // Read serial number (2 bytes) and convert to unsigned
        short serialNumber = buffer.getShort();
        int unsignedSerial = serialNumber & 0xFFFF;

        logger.info("Login request - IMEI: {}, Serial (unsigned): {}", imei, unsignedSerial);

        // Store both signed and unsigned versions
        parsedData.put("serialNumber", serialNumber);
        parsedData.put("unsignedSerial", unsignedSerial);
        message.setSerialNumber(serialNumber);

        // Handle VL03 extension if present
        byte vl03Extension = handleVl03Extension(buffer, variant, parsedData);

        // Manage device session
        DeviceSession session = manageDeviceSession(imei, serialNumber, ctx);
        if (session == null) {
            throw new ProtocolException("Failed to create session for IMEI: " + imei);
        }

        // Generate response using unsigned serial number
        byte[] response = generateLoginResponse(variant, serialNumber, vl03Extension);
        if (response == null) {
            throw new ProtocolException("Failed to generate login response");
        }

        logger.info("Sending login response: {}", Hex.encodeHexString(response));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
        logger.info("Raw bytes sent: {}", Hex.encodeHexString(response));



        // Populate message
        message.setResponseData(response);
        message.setResponseRequired(true);
        message.setImei(imei);
        message.setMessageType("LOGIN");
        message.getParsedData().put("sessionId", session.getSessionId());
        message.getParsedData().put("deviceId", generateDeviceId(imei));

        logger.info("Processed login for IMEI: {}", imei);
        return message;
    }

    private DeviceSession manageDeviceSession(String imei, short serialNumber, ChannelHandlerContext ctx) {
        if (imei == null) {
            throw new IllegalArgumentException("IMEI cannot be null");
        }

        Channel channel = ctx != null ? ctx.channel() : null;
        SocketAddress remoteAddress = ctx != null ? ctx.channel().remoteAddress() : null;

        return activeSessions.compute(imei, (key, existing) -> {
            if (existing != null) {
                // Update existing session with new channel info
                if (channel != null) {
                    existing.setChannel(channel);
                    existing.setRemoteAddress(remoteAddress);
                }
                // Only update serial number if it's different
                if (!existing.hasSameSerialNumber(serialNumber)) {
                    existing.setSerialNumber(serialNumber);
                    logger.info("Updated serial number for existing session IMEI: {}", imei);
                }
                existing.updateLastActivity();
                logger.info("Using existing session for IMEI: {}", imei);
                return existing;
            }

            // Only create new session if we have a channel
            if (channel == null) {
                logger.warn("Cannot create session without channel for IMEI: {}", imei);
                return null;
            }

            logger.info("Creating new session for IMEI: {}", imei);
            DeviceSession newSession = new DeviceSession(
                    generateDeviceId(imei),
                    imei,
                    "GT06",
                    channel,
                    remoteAddress
            );
            newSession.setSerialNumber(serialNumber);
            return newSession;
        });
    }

    protected String extractImei(byte[] imeiBytes) throws ProtocolException {
        if (imeiBytes == null || imeiBytes.length != 8) {
            throw new ProtocolException("Invalid IMEI bytes length");
        }

        // Convert packed BCD to string
        StringBuilder imei = new StringBuilder(16);
        for (byte b : imeiBytes) {
            // Each byte contains two BCD digits
            int highNibble = (b >> 4) & 0x0F;
            int lowNibble = b & 0x0F;

            // Validate each nibble is a valid BCD digit (0-9)
            if (highNibble > 9 || lowNibble > 9) {
                throw new ProtocolException("Invalid BCD digit in IMEI");
            }

            imei.append(highNibble).append(lowNibble);
        }

        // The IMEI should be exactly 15 digits
        String imeiStr = imei.toString();

        // Remove any leading zeros that would make it too short
        while (imeiStr.length() > 15 && imeiStr.startsWith("0")) {
            imeiStr = imeiStr.substring(1);
        }

        // Validate length and format
        if (imeiStr.length() != 15 || !imeiStr.matches("^\\d{15}$")) {
            throw new ProtocolException("Invalid IMEI format: " + imeiStr);
        }

        logger.info("Extracted valid IMEI: {}", imeiStr);
        return imeiStr;
    }

    /*private byte[] generateLoginResponse(Variant variant, short serialNumber, byte vl03Extension) {
        try {
            if (variant == Variant.VL03) {
                byte[] response = new byte[14];
                response[0] = PROTOCOL_HEADER_1;
                response[1] = PROTOCOL_HEADER_2;
                response[2] = 0x09;  // Length (9 bytes following)
                response[3] = PROTOCOL_LOGIN;
                response[4] = (byte)(serialNumber >> 8);
                response[5] = (byte)(serialNumber);
                response[6] = 0x01;  // Success status
                response[7] = 0x01;  // VL03-specific extension byte

                // Calculate checksum
                ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 6);
                int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer) ^ 0xFFFF;

                response[8] = (byte)(checksum >> 8);
                response[9] = (byte)(checksum);
                response[10] = 0x0D;
                response[11] = 0x0A;

                return response;
            } else {
                // Standard GT06 response
                byte[] response = new byte[10];
                response[0] = PROTOCOL_HEADER_1;
                response[1] = PROTOCOL_HEADER_2;
                response[2] = 0x05; // Length
                response[3] = PROTOCOL_LOGIN;
                response[4] = (byte)(serialNumber >> 8);
                response[5] = (byte)(serialNumber);
                response[6] = 0x01; // Success status

                // Calculate checksum
                ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 5);
                int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer) ^ 0xFFFF;

                response[7] = (byte)(checksum >> 8);
                response[8] = (byte)(checksum);
                response[9] = 0x0A; // Termination byte

                return response;
            }
        } catch (Exception e) {
            logger.error("Failed to generate login response", e);
            return null;
        }
    }*/

    private enum Variant {
        STANDARD, VL03, UNKNOWN
    }


    //@Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);

                logger.info("Processing GT06 message: {}", Hex.encodeHexString(data));

                DeviceMessage message = handle(data);
                if (message != null) {
                    ctx.fireChannelRead(message);
                    logger.info("Forwarding message with serialNumber: {}", message.getSerialNumber());
                }
            } catch (Exception e) {
                logger.error("Error processing message", e);
            } finally {
                buf.release();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private Variant detectVariant(ByteBuffer buffer) {
        // Check for VL03 specific markers
        if (buffer.remaining() > 10) {
            int pos = buffer.position();
            // VL03 often has specific patterns in the login packet
            if (buffer.get(pos + 10) == 0x01) {  // VL03 marker
                return Variant.VL03;
            }
        }
        return Variant.STANDARD;
    }

    private void validatePacket(byte[] data) throws ProtocolException {

        // Add explicit length check for login packets


        if (data[3] == PROTOCOL_LOGIN && data.length != LOGIN_PACKET_LENGTH) {


            throw new ProtocolException("Invalid login packet length");


        }





        if (data.length < MIN_PACKET_LENGTH) {


            throw new ProtocolException(String.format(


                    "Packet too short (%d bytes), minimum required %d",


                    data.length, MIN_PACKET_LENGTH));


        }





        // Verify header


        if (data[0] != PROTOCOL_HEADER_1 || data[1] != PROTOCOL_HEADER_2) {


            throw new ProtocolException(String.format(


                    "Invalid protocol header: 0x%02X 0x%02X (expected 0x78 0x78)",


                    data[0], data[1]));


        }





        // Verify length matches actual packet size


        int declaredLength = data[2] & 0xFF;


        if (data.length != declaredLength + 5) { // 2 header + 1 length + 2 tail


            throw new ProtocolException(String.format(


                    "Packet length mismatch. Declared: %d, actual: %d",


                    declaredLength, data.length - 5));


        }





        // Verify checksum using CRC-16/X25


        int receivedChecksum = ((data[data.length - 4] & 0xFF) << 8) | (data[data.length - 3] & 0xFF);


        ByteBuffer checksumBuffer = ByteBuffer.wrap(data, 2, data.length - 6);


        int calculatedChecksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);





        if (receivedChecksum != calculatedChecksum) {


            throw new ProtocolException(String.format(


                    "Checksum mismatch (received: 0x%04X, calculated: 0x%04X)",


                    receivedChecksum, calculatedChecksum));


        }





        // Verify termination bytes


        if (data[data.length - 2] != 0x0D || data[data.length - 1] != 0x0A) {


            throw new ProtocolException(String.format(


                    "Invalid packet termination: 0x%02X 0x%02X (expected 0x0D 0x0A)",


                    data[data.length - 2], data[data.length - 1]));


        }


    }



    private byte[] generateVl03LoginResponse(short serialNumber, byte vl03Extension) {
        byte[] response = new byte[14];
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x09;  // Length (9 bytes following)
        response[3] = PROTOCOL_LOGIN;
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber);
        response[6] = 0x01;  // Success status
        response[7] = vl03Extension;  // VL03-specific extension byte

        // Calculate checksum for bytes 2-7 (length through vl03Extension)
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 6);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[8] = (byte)(checksum >> 8);
        response[9] = (byte)(checksum);

        // Terminal bytes
        response[10] = 0x0D;
        response[11] = 0x0A;

        return response;
    }

    private byte[] generateStandardLoginResponse(short serialNumber) {
        byte[] response = new byte[10];
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x05; // Length
        response[3] = PROTOCOL_LOGIN;
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber);
        response[6] = 0x01; // Success status

        // Calculate checksum
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 5);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[7] = (byte)(checksum >> 8);
        response[8] = (byte)(checksum);
        response[9] = 0x0A; // Termination byte

        return response;
    }

    private DeviceMessage handleGps(ByteBuffer buffer, DeviceMessage message,
                                    Map<String, Object> parsedData, Variant variant) throws Exception {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        Position position = parseGpsData(buffer);
        parsedData.put("position", position);

        byte[] response = generateStandardResponse(PROTOCOL_GPS, (short)0, (byte)0x01);
        parsedData.put("response", response);

        message.setImei(imei);
        message.setMessageType("GPS");
        acknowledgementHandler.write(null, new AcknowledgementHandler.EventHandled(response), null);

        return message;
    }

    public Position parseGpsData(ByteBuffer buffer) {
        Position position = new Position();
        Device device = new Device();
        device.setImei(lastValidImei.get());
        device.setProtocolType("GT06");
        position.setDevice(device);

        position.setTimestamp(LocalDateTime.of(
                2000 + (buffer.get() & 0xFF),
                buffer.get() & 0xFF,
                buffer.get() & 0xFF,
                buffer.get() & 0xFF,
                buffer.get() & 0xFF,
                buffer.get() & 0xFF));

        position.setSatellites(buffer.get() & 0xFF);
        position.setLatitude(buffer.getInt() / 1800000.0);
        position.setLongitude(buffer.getInt() / 1800000.0);
        position.setSpeed((buffer.get() & 0xFF) * 1.852);
        position.setCourse((double) (buffer.getShort() & 0xFFFF));

        int flags = buffer.getShort() & 0xFFFF;
        position.setValid((flags & 0x1000) != 0);
        position.setIgnition((flags & 0x8000) != 0);

        return position;
    }

    /*private DeviceMessage handleHeartbeat(ByteBuffer buffer, DeviceMessage message,
                                          Map<String, Object> parsedData) throws Exception {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        // Parse heartbeat info if needed
        byte[] infoBytes = new byte[buffer.remaining()];
        buffer.get(infoBytes);

        // Generate response
        byte[] response = new byte[10];
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x05;
        response[3] = PROTOCOL_HEARTBEAT;
        response[4] = infoBytes.length > 0 ? infoBytes[0] : 0x00;
        response[5] = infoBytes.length > 1 ? infoBytes[1] : 0x00;

        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 4);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[6] = (byte)(checksum >> 8);
        response[7] = (byte)(checksum);
        response[8] = 0x0D;
        response[9] = 0x0A;

        parsedData.put("response", response);
        message.setResponseData(response);
        message.setResponseRequired(true);
        message.setImei(imei);
        message.setMessageType("HEARTBEAT");

        return message;
    }*/

    private DeviceMessage handleAlarm(ByteBuffer buffer, DeviceMessage message,
                                      Map<String, Object> parsedData, Variant variant) throws Exception {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        Position position = parseGpsData(buffer);
        position.setAlarmType(extractAlarmType(buffer, variant));

        byte[] response = variant == Variant.VL03 ?
                generateVl03AlarmResponse() :
                generateStandardResponse(PROTOCOL_ALARM, (short)0, (byte)0x01);

        parsedData.put("response", response);
        parsedData.put("position", position);

        message.setImei(imei);
        message.setMessageType("ALARM");
        acknowledgementHandler.write(null, new AcknowledgementHandler.EventHandled(response), null);

        return message;
    }

    private String extractAlarmType(ByteBuffer buffer, Variant variant) {
        int alarmCode = buffer.get() & 0xFF;

        if (variant == Variant.VL03) {
            switch (alarmCode) {
                case 0xA0: return "VL03_HARD_ACCELERATION";
                case 0xA1: return "VL03_HARD_BRAKING";
                case 0xA2: return "VL03_CRASH_DETECTION";
                case 0xA3: return "VL03_TOW_ALARM";
                case 0xA4: return "VL03_JAMMING_DETECTION";
                case 0xA5: return "VL03_FATIGUE_DRIVING";
            }
        }

        switch (alarmCode) {
            case 0x01: return "SOS";
            case 0x02: return "LOW_BATTERY";
            case 0x03: return "POWER_CUT";
            case 0x04: return "VIBRATION";
            case 0x05: return "ENTER_FENCE";
            case 0x06: return "EXIT_FENCE";
            case 0x09: return "OVER_SPEED";
            case 0x10: return "POWER_ON";
            default: return "UNKNOWN_ALARM_" + alarmCode;
        }
    }

    private DeviceMessage handleVl03Extended(ByteBuffer buffer, DeviceMessage message,
                                             Map<String, Object> parsedData) {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI for VL03 extended message");
        }

        int extensionType = buffer.get() & 0xFF;
        Position position = parseVl03GpsData(buffer);

        byte[] response = generateVl03Response(extensionType);
        parsedData.put("response", response);
        parsedData.put("position", position);

        message.setImei(imei);
        message.setMessageType("VL03_EXTENDED");
        return message;
    }

    private Position parseVl03GpsData(ByteBuffer buffer) {
        Position position = parseGpsData(buffer);
        // Add VL03-specific parsing here
        return position;
    }

    private byte[] generateVl03Response(int extensionType) {
        ByteBuffer buf = ByteBuffer.allocate(12)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte)0x07)
                .put(VL03_PROTOCOL_EXTENDED)
                .put((byte)extensionType)
                .putShort((short)0x0000);

        byte[] data = buf.array();
        int checksum = Checksum.crc16(Checksum.CRC16_X25, ByteBuffer.wrap(data, 2, 5));

        return ByteBuffer.allocate(12)
                .put(data, 0, 8)
                .putShort((short)checksum)
                .put((byte)0x0D)
                .put((byte)0x0A)
                .array();
    }

    private byte[] generateVl03AlarmResponse() {
        byte[] response = new byte[14];
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x0B;
        response[3] = PROTOCOL_ALARM;
        response[4] = 0x01;
        response[5] = 0x00;
        response[6] = 0x00;

        LocalDateTime now = LocalDateTime.now();
        response[7] = (byte)(now.getYear() - 2000);
        response[8] = (byte)now.getMonthValue();
        response[9] = (byte)now.getDayOfMonth();
        response[10] = (byte)now.getHour();
        response[11] = (byte)now.getMinute();
        response[12] = (byte)now.getSecond();

        int checksum = Checksum.crc16(Checksum.CRC16_X25, ByteBuffer.wrap(response, 2, 11));
        response[13] = (byte)(checksum >> 8);
        response[14] = (byte)(checksum & 0xFF);
        response[15] = 0x0D;
        response[16] = 0x0A;

        return response;
    }

    private byte handleVl03Extension(ByteBuffer buffer, Variant variant, Map<String, Object> parsedData) {
        byte extension = 0;
        if (variant == Variant.VL03 && buffer.remaining() >= 1) {
            extension = buffer.get();
            parsedData.put("vl03Extension", extension);
        }
        return extension;
    }

    private byte[] generateStandardResponse(byte protocol, short serialNumber, byte status) {
        byte[] response = new byte[10];
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x05;
        response[3] = protocol;
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber);
        response[6] = status;

        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 5);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[7] = (byte)(checksum >> 8);
        response[8] = (byte)(checksum);
        response[9] = 0x0A;
        return response;
    }

    private byte[] generateErrorResponse(Exception error) {
        return generateStandardResponse(PROTOCOL_ERROR, (short)0, getErrorCode(error));
    }

    private byte getErrorCode(Exception error) {
        if (error.getMessage().contains("IMEI")) return 0x01;
        if (error.getMessage().contains("checksum")) return 0x02;
        if (error.getMessage().contains("header")) return 0x03;
        if (error.getMessage().contains("length")) return 0x04;
        return (byte)0xFF;
    }



    public String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredSessions() {
        activeSessions.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                logger.debug("Removing expired session for IMEI: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    @Override
    public boolean supports(String protocolType) {
        return "GT06".equalsIgnoreCase(protocolType);
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        return "GT06".equalsIgnoreCase(protocol);
    }

    @Override
    public Position parsePosition(byte[] rawMessage) {
        try {
            if (rawMessage == null || rawMessage.length < 12 ||
                    rawMessage[0] != PROTOCOL_HEADER_1 || rawMessage[1] != PROTOCOL_HEADER_2) {
                return null;
            }

            ByteBuffer buffer = ByteBuffer.wrap(rawMessage).order(ByteOrder.BIG_ENDIAN);
            buffer.position(3);
            byte protocol = buffer.get();

            if (protocol == PROTOCOL_GPS || protocol == PROTOCOL_ALARM) {
                return parseGpsData(buffer);
            }
        } catch (Exception e) {
            logger.error("Error parsing position", e);
        }
        return null;
    }

    @Override
    public byte[] generateResponse(Position position) {
        return generateStandardResponse(PROTOCOL_LOGIN, (short)0, (byte)0x01);
    }
}