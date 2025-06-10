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
import org.springframework.stereotype.Component;
import java.net.SocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.*;
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
    private static final byte PROTOCOL_GPS_EXTENDED = (byte) 0xA0; // Added for A0 protocol
    private static final int MIN_PACKET_LENGTH = 12;
    private static final int LOGIN_PACKET_LENGTH = 22;

    private final AtomicReference<String> lastValidImei = new AtomicReference<>();
    private final Map<String, DeviceSession> activeSessions = new ConcurrentHashMap<>();

    // VL03-specific constants
    private static final byte VL03_PROTOCOL_EXTENDED = 0x26;

    // New constants for 0x7979 header and info report protocol
    private static final byte PROTOCOL_HEADER_79_1 = 0x79;
    private static final byte PROTOCOL_HEADER_79_2 = 0x79;
    private static final byte PROTOCOL_INFO_REPORT = 0x01; // For 0x7979 packets

    @Autowired
    private AcknowledgementHandler acknowledgementHandler;

    @Autowired
    public Gt06Handler(SessionManager sessionManager,
                       ProtocolDetector protocolDetector,
                       AcknowledgementHandler acknowledgementHandler) {
        super(sessionManager, protocolDetector,null,null);
        this.acknowledgementHandler = acknowledgementHandler;
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buf,
                            ProtocolDetector.ProtocolDetectionResult result) {
        List<DeviceMessage> messages = new ArrayList<>();

        while (buf.readableBytes() >= MIN_PACKET_LENGTH) {
            buf.markReaderIndex(); // Mark the current read index

            byte header1 = buf.getByte(buf.readerIndex());
            byte header2 = buf.getByte(buf.readerIndex() + 1);

            int packetSize = -1; // Determined based on header

            if (header1 == PROTOCOL_HEADER_1 && header2 == PROTOCOL_HEADER_2) { // Standard 0x7878 GT06
                if (buf.readableBytes() < 4) { // Need at least header(2) + length(1) + protocol(1)
                    buf.resetReaderIndex();
                    break;
                }
                int declaredLength = buf.getByte(buf.readerIndex() + 2) & 0xFF; // Length byte at index 2 (relative to packet start)
                packetSize = 2 + 1 + declaredLength + 2 + 2; // header (2) + length (1) + data (declaredLength) + checksum (2) + footer (2)

            } else if (header1 == PROTOCOL_HEADER_79_1 && header2 == PROTOCOL_HEADER_79_2) { // 0x7979 packet
                // For 0x7979 packets, the length is not at a fixed offset like 0x7878.
                // We need to find the 0x0D0A footer to determine the packet size.
                // Minimum size for 0x7979 is 2 (header) + 1 (protocol) + 2 (serial) + 2 (checksum) + 2 (footer) = 9 bytes.
                if (buf.readableBytes() < 9) {
                    buf.resetReaderIndex();
                    break;
                }

                int potentialPacketEnd = buf.indexOf(buf.readerIndex(), buf.writerIndex(), (byte)0x0D);
                if (potentialPacketEnd != -1 && buf.readableBytes() >= potentialPacketEnd + 1 - buf.readerIndex()) {
                    if (buf.getByte(potentialPacketEnd + 1) == (byte)0x0A) {
                        packetSize = potentialPacketEnd + 2 - buf.readerIndex(); // Length including 0x0D0A
                    } else {
                        buf.skipBytes(1); // Skip the problematic byte to avoid infinite loop
                        logger.warn("Found 0x0D but not followed by 0x0A for 0x7979 packet. Skipping byte.");
                        continue;
                    }
                } else {
                    // Not enough data for a full packet with 0x0D0A terminator, or terminator not found.
                    buf.resetReaderIndex(); // Reset to the start of the potential packet
                    break; // Exit the loop, wait for more data
                }

            } else { // Unrecognized header
                buf.skipBytes(1); // Skip the problematic first byte
                logger.warn("Non-GT06 or unrecognized header found. Skipping byte.");
                continue; // Continue to the next byte
            }

            // Ensure we have enough bytes for the complete packet
            if (packetSize == -1 || buf.readableBytes() < packetSize) {
                buf.resetReaderIndex(); // Not enough data yet for a complete packet
                break;
            }

            // Extract the complete packet data into a new byte array
            byte[] data = new byte[packetSize];
            buf.readBytes(data); // Read the full packet

            // Now, process the packet. The `handle` method will then validate and parse.
            DeviceMessage message = handle(data, ctx);
            if (message != null && message.getImei() != null) {
                message.addParsedData("deviceId", generateDeviceId(message.getImei()));
                messages.add(message);
            } else if (message != null && message.getError() != null) {
                logger.error("Packet processing failed for data {}: {}", Hex.encodeHexString(data), message.getError());
            }
        }
        return messages.isEmpty() ? null : (messages.size() == 1 ? messages.get(0) : messages);
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        return handle(data, null);
    }

    @Override
    public DeviceMessage handle(byte[] data, ChannelHandlerContext ctx) throws ProtocolException {
        logger.info("Processing GT06 packet: {}", Hex.encodeHexString(data));
        DeviceMessage message = new DeviceMessage();
        message.setProtocolType("GT06");
        Map<String, Object> parsedData = new HashMap<>();
        message.setParsedData(parsedData);

        try {
            logger.info("Raw input packet ({} bytes): {}", data.length, bytesToHex(data));
            validatePacket(data); // Validate the full packet first

            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            byte header1 = buffer.get();
            byte header2 = buffer.get();

            byte protocol;
            if (header1 == PROTOCOL_HEADER_1 && header2 == PROTOCOL_HEADER_2) {
                buffer.get(); // Skip length byte for 0x7878 packets
                protocol = buffer.get(); // This is the actual protocol type
            } else if (header1 == PROTOCOL_HEADER_79_1 && header2 == PROTOCOL_HEADER_79_2) {
                protocol = buffer.get(); // For 0x7979 packets, data[2] is the protocol type
            } else {
                throw new ProtocolException("Unsupported packet header in handle: 0x" + String.format("%02X%02X", header1, header2));
            }

            // Add logging for unknown protocols
            if (!isSupportedProtocol(protocol, header1, header2)) { // Pass headers to distinguish 0x01 types
                logger.warn("Received unsupported protocol type: 0x{}",
                        String.format("%02X", protocol));
                return createUnsupportedProtocolMessage(data, protocol);
            }

            logger.info("Detected GT06 packet - Protocol: 0x{}", String.format("%02X", protocol));

            Variant variant = detectVariant(buffer); // Variant detection might need adjustment based on headers
            logger.debug("Detected device variant: {}", variant);

            // Handle based on header and protocol
            if (header1 == PROTOCOL_HEADER_79_1 && header2 == PROTOCOL_HEADER_79_2 && protocol == PROTOCOL_INFO_REPORT) {
                return handleInfoReport(buffer, message, parsedData, variant, ctx);
            } else {
                switch (protocol & 0xFF) {
                    case 0x01: // Login for 0x7878
                        return handleLogin(buffer, message, parsedData, variant, ctx);
                    case 0x12:
                        return handleGps(buffer, message, parsedData, variant);
                    case 0x13:
                        return handleHeartbeat(buffer, message, parsedData);
                    case 0x8A:
                        return handleHeartbeat(buffer, message, parsedData); // you can alias 0x8A to heartbeat
                    case 0xA0: // PROTOCOL_GPS_EXTENDED
                        return handleGpsExtended(buffer, message, parsedData, variant);
                    case 0x26:
                        return handleVl03Extended(buffer, message, parsedData);
                    case 0x16:
                        return handleAlarm(buffer, message, parsedData, variant);
                    default:
                        throw new ProtocolException("Unsupported GT06 protocol type: 0x" + String.format("%02X", protocol));
                }
            }
        } catch (Exception e) {
            logger.error("Error processing packet: {}", Hex.encodeHexString(data), e);
            message.setError(e.getMessage());
            message.setResponseData(generateErrorResponse(e));
            message.setResponseRequired(true);
            return message;
        }
    }

    private boolean isSupportedProtocol(byte protocol, byte header1, byte header2) {
        if (header1 == PROTOCOL_HEADER_1 && header2 == PROTOCOL_HEADER_2) { // Standard 0x7878
            switch (protocol & 0xFF) {
                case 0x01: // LOGIN
                case 0x12: // GPS
                case 0x13: // HEARTBEAT
                case 0x8A: // ALIAS HEARTBEAT
                case 0xA0: // GPS EXTENDED
                case 0x26: // VL03 EXTENDED
                case 0x16: // ALARM
                    return true;
                default:
                    return false;
            }
        } else if (header1 == PROTOCOL_HEADER_79_1 && header2 == PROTOCOL_HEADER_79_2) { // 0x7979 header
            switch (protocol & 0xFF) {
                case 0x01: // Information report
                    return true;
                default:
                    return false;
            }
        }
        return false; // Unknown header
    }

    private DeviceMessage createUnsupportedProtocolMessage(byte[] data, byte protocol) {
        DeviceMessage message = new DeviceMessage();
        message.setProtocolType("GT06");
        message.setMessageType("UNSUPPORTED_PROTOCOL");
        message.setError("Unsupported protocol type: 0x" + String.format("%02X", protocol));

        // Optionally include the raw data
        message.setRawData(data);

        return message;
    }

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
        //byte[] response = generateLoginResponse(variant, (short)unsignedSerial, vl03Extension);
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

    private DeviceMessage handleGpsExtended(ByteBuffer buffer, DeviceMessage message,
                                            Map<String, Object> parsedData, Variant variant) throws Exception {
        try {
            // Ensure Position is initialized
            if (message.getPosition() == null) {
                message.setPosition(new Position());
            }

            // Read timestamp (6 bytes: YY-MM-DD-HH-MM-SS)
            LocalDateTime timestamp = readDateTime(buffer);
            parsedData.put("timestamp", timestamp);
            message.setTimestamp(timestamp);

            // Read Satellite count and GPS Status (1 byte)
            int satellitesAndStatus = buffer.get() & 0xFF;
            int satelliteCount = satellitesAndStatus & 0x3F; // Bits 0-5
            // GPS positioning status: 00: Unpositioned, 01: 2D, 10: 3D
            int gpsPositioningStatus = (satellitesAndStatus >> 6) & 0x03; // Bits 6-7
            parsedData.put("satelliteCount", satelliteCount);
            parsedData.put("gpsPositioningStatus", gpsPositioningStatus);

            // Read raw latitude and longitude (4 bytes each, signed int)
            // GT06 format: raw_value / 1,800,000.0 to get decimal degrees
            int rawLatitude = buffer.getInt();
            int rawLongitude = buffer.getInt();

            // Read speed (1 byte, km/h)
            int speed = buffer.get() & 0xFF;
            parsedData.put("speed", speed);
            message.setSpeed(speed);

            // Read course and status (2 bytes)
            int courseStatus = buffer.getShort() & 0xFFFF;
            parsedData.put("courseStatus", courseStatus);
            message.setCourse(courseStatus & 0x03FF); // Bits 0-9 for Course

            // --- Determine Latitude and Longitude with correct sign ---
            // Bit 13 (0x2000) of Course & Status indicates North (0) or South (1)
            boolean isSouth = (courseStatus & 0x2000) != 0;
            //isSouth = true; //hardcode for latitude correctness - REMOVE THIS HARDCODE IN PRODUCTION
            // Bit 14 (0x4000) of Course & Status indicates East (0) or West (1)
            boolean isWest = (courseStatus & 0x4000) != 0;

            double latitude = rawLatitude / 1_800_000.0;
            double longitude = rawLongitude / 1_800_000.0;

            // Apply the sign based on the N/S bit if the raw value is positive
            // If the raw value is already negative, this will keep it negative.
            if (isSouth && latitude > 0) { // Only negate if it's South and currently positive
                latitude = -latitude;
            } else if (!isSouth && latitude < 0) { // Only make positive if it's North and currently negative
                latitude = -latitude;
            }


            // Apply the sign based on the E/W bit if the raw value is positive
            if (isWest && longitude > 0) { // Only negate if it's West and currently positive
                longitude = -longitude;
            } else if (!isWest && longitude < 0) { // Only make positive if it's East and currently negative
                longitude = -longitude;
            }

            //if (latitude > 0) latitude = -latitude; //hardcode for latitude correctness - REMOVE THIS HARDCODE IN PRODUCTION


            message.getPosition().setLatitude(latitude);
            message.getPosition().setLongitude(longitude);
            // Set validity based on GPS positioning status
            message.getPosition().setValid(gpsPositioningStatus == 0x01 || gpsPositioningStatus == 0x02); // 2D or 3D means valid
            parsedData.put("latitude", latitude);
            parsedData.put("longitude", longitude);

            // Debug logging (optional, adjust as needed)
            logger.info("Raw coordinates - Lat: 0x{}, Lon: 0x{}",
                    Integer.toHexString(rawLatitude), Integer.toHexString(rawLongitude));
            logger.info("Parsed GPS - Lat: {}, Lon: {}, Sat: {}, Status: {}, Time: {}, CourseStatus: 0x{}, isSouth: {}, isWest: {}",
                    latitude, longitude, satelliteCount, gpsPositioningStatus, timestamp, Integer.toHexString(courseStatus), isSouth, isWest);


            // --- Start of variable/optional fields ---

            // Read LBS Length (1 byte)
            int lbsLength = buffer.get() & 0xFF;
            parsedData.put("lbsLength", lbsLength);

            if (lbsLength > 0) {
                // LBS data is present (typically 9 bytes for single base station:
                // MCC (2), MNC (1), LAC (2), Cell ID (3), RSSI (1)
                if (buffer.remaining() >= lbsLength) {
                    int mcc = buffer.getShort() & 0xFFFF;
                    int mnc = buffer.get() & 0xFF;
                    int lac = buffer.getShort() & 0xFFFF;
                    int cellId = ((buffer.get() & 0xFF) << 16) | ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
                    int signalStrength = buffer.get() & 0xFF;
                    parsedData.put("mcc", mcc);
                    parsedData.put("mnc", mnc);
                    parsedData.put("lac", lac);
                    parsedData.put("cellId", cellId);
                    parsedData.put("signalStrength", signalStrength);
                    // Consume any remaining LBS bytes if lbsLength is larger than default 9-byte LBS
                    if (lbsLength > 9) { // If LBS data is longer than the common 9-byte structure
                        buffer.position(buffer.position() + lbsLength - 9);
                    }
                } else {
                    logger.warn("Incomplete LBS data. Expected: {} bytes, Available: {}", lbsLength, buffer.remaining());
                    // Decide how to handle incomplete data (e.g., skip to next known field)
                }
            }

            // --- I/O Alarm & Status fields (fixed order after LBS data or LBS Length 00) ---
            // Based on example packet: 00 28 50 20 00 00 27 79 00 00 00 00 01 39 89 01 01 00 00 00 04 F6 7C

            if (buffer.remaining() >= 1) { // Voltage/Status (e.g., 0x28 in your example)
                int voltageStatus = buffer.get() & 0xFF;
                parsedData.put("voltageStatus", voltageStatus);
            }
            if (buffer.remaining() >= 1) { // GSM Signal (e.g., 0x50 in your example)
                int gsmSignal = buffer.get() & 0xFF;
                parsedData.put("gsmSignal", gsmSignal);
            }
            if (buffer.remaining() >= 2) { // Alarm/Language/Terminal Info (e.g., 0x2000 in your example)
                int alarmLanguageInfo = buffer.getShort() & 0xFFFF;
                parsedData.put("alarmLanguageInfo", alarmLanguageInfo);
            }
            if (buffer.remaining() >= 4) { // Mileage (4 bytes)
                long mileage = buffer.getInt() & 0xFFFFFFFFL; // Read as unsigned 4-byte integer
                parsedData.put("mileage", mileage);
            }
            if (buffer.remaining() >= 4) { // Continuous Driving Time (4 bytes)
                long drivingTime = buffer.getInt() & 0xFFFFFFFFL; // Read as unsigned 4-byte integer
                parsedData.put("drivingTime", drivingTime);
            }

            // --- Other I/O Data (variable/custom) ---
            // These bytes follow the standard fixed I/O fields. In your example, it's 9 bytes.
            // 01 39 89 01 01 00 00 00 04
            // The Serial Number (2 bytes) is the last field before the Checksum.
            // So, read remaining bytes until 2 bytes before the end of the buffer (which is for Serial Number).

            int remainingBytesBeforeSerial = buffer.remaining() - 2; // Assume 2 bytes for serial number

            if (remainingBytesBeforeSerial > 0) {
                byte[] otherIoData = new byte[remainingBytesBeforeSerial];
                buffer.get(otherIoData);
                parsedData.put("otherIoData", Hex.encodeHexString(otherIoData));
            }

            // Information Serial Number (2 bytes)
            // This assumes buffer.remaining() is exactly 2 bytes after consuming all otherIoData.
            short serialNumber = buffer.getShort();
            parsedData.put("serialNumber", serialNumber);
            message.setSerialNumber(serialNumber);

            // At this point, buffer.remaining() should be 0 if the buffer was precisely
            // the data unit (Protocol Type to Serial Number), or 2 if it includes checksum.
            // Checksum validation typically happens at a lower layer or before parsing.

            message.setMessageType("GPS_EXTENDED");
            message.setImei(lastValidImei.get()); // Assuming lastValidImei is correctly set from login/IMEI packet
            parsedData.put("deviceId", generateDeviceId(message.getImei()));

            logger.info("Processed GPS - Lat: {}, Lon: {}, Speed: {}, Valid: {}, Time: {}, Serial: {}",
                    latitude, longitude, speed, message.getPosition().getValid(), timestamp, serialNumber);

            // Generate response (assuming PROTOCOL_GPS is appropriate for A0 response, and 0x01 is status success)
            byte[] response = generateStandardResponse(PROTOCOL_GPS_EXTENDED, serialNumber, (byte) 0x01);
            message.setResponseData(response);
            message.setResponseRequired(true);

            return message;

        } catch (BufferUnderflowException e) {
            logger.error("Error decoding extended GPS packet due to insufficient bytes", e);
            throw new ProtocolException("Incomplete GPS extended packet", e);
        } catch (Exception e) {
            logger.error("Error handling extended GPS packet", e);
            throw e; // Re-throw or handle as appropriate
        }
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

    private byte[] generateLoginResponse(Variant variant, short serialNumber, byte vl03Extension) {
        try {
            if (variant == Variant.VL03) {
                byte[] response = new byte[14];
                response[0] = PROTOCOL_HEADER_1;
                response[1] = PROTOCOL_HEADER_2;
                response[2] = 0x09;  // Length (9 bytes following)
                response[3] = PROTOCOL_LOGIN;
                // Use unsigned serial number in response
                response[4] = (byte)((serialNumber >> 8) & 0xFF);
                response[5] = (byte)(serialNumber & 0xFF);
                response[6] = 0x01;  // Success status
                response[7] = 0x01;  // VL03-specific extension byte

                // Calculate checksum
                ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 6);
                int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

                response[8] = (byte)(checksum >> 8);
                response[9] = (byte)(checksum);
                response[10] = 0x0D;
                response[11] = 0x0A;

                return response;
            } else {
                // Standard GT06 response
                byte[] response = new byte[10];

                response[0] = 0x78;
                response[1] = 0x78;
                response[2] = 0x05;
                response[3] = 0x01;
                response[4] = (byte)(serialNumber >> 8);
                response[5] = (byte)(serialNumber & 0xFF);

                ByteBuffer crcBuf = ByteBuffer.wrap(response, 2, 4); // 0x05 0x01 [serialHigh] [serialLow]
                int crc = Checksum.crc16(Checksum.CRC16_X25, crcBuf);

                response[6] = (byte)(crc >> 8);
                response[7] = (byte)(crc & 0xFF);
                response[8] = 0x0D;
                response[9] = 0x0A;

                logger.info("Generated login response XXX: {}", Hex.encodeHexString(response));
                return response;
            }
        } catch (Exception e) {
            logger.error("Failed to generate login response", e);
            return null;
        }
    }

    private enum Variant {
        STANDARD, VL03, UNKNOWN
    }

    private Variant detectVariant(ByteBuffer buffer) {
        // Check for VL03 specific markers
        if (buffer.remaining() > 10) {
            int pos = buffer.position();
            // VL03 often has specific patterns in the login packet
            // Need to be careful here, as VL03 marker could be a coincidence.
            // A more robust detection might involve a longer sequence or known data points.
            // For now, based on provided VL03 info, 0x01 at a specific offset might be a hint.
            if (buffer.get(pos + 10) == 0x01) {  // VL03 marker
                return Variant.VL03;
            }
        }
        return Variant.STANDARD;
    }

    private void validatePacket(byte[] data) throws ProtocolException {
        // Add explicit length check for login packets
        if (data == null || data.length < MIN_PACKET_LENGTH) {
            throw new ProtocolException("Packet is null or too short");
        }

        byte header1 = data[0];
        byte header2 = data[1];

        if (!((header1 == PROTOCOL_HEADER_1 && header2 == PROTOCOL_HEADER_2) ||
                (header1 == PROTOCOL_HEADER_79_1 && header2 == PROTOCOL_HEADER_79_2))) {
            throw new ProtocolException(String.format(
                    "Invalid protocol header: 0x%02X 0x%02X (expected 0x78 0x78 or 0x79 0x79)",
                    header1, header2));
        }

        // Verify termination bytes
        if (data[data.length - 2] != 0x0D || data[data.length - 1] != 0x0A) {
            throw new ProtocolException(String.format(
                    "Invalid packet termination: 0x%02X 0x%02X (expected 0x0D 0x0A)",
                    data[data.length - 2], data[data.length - 1]));
        }

        if (header1 == PROTOCOL_HEADER_1 && header2 == PROTOCOL_HEADER_2) { // Standard 0x7878 GT06
            int declaredLength = data[2] & 0xFF;
            // Total packet length: 2 (header) + 1 (length byte) + declaredLength (data, including protocol byte) + 2 (checksum) + 2 (footer)
            if (data.length != (2 + 1 + declaredLength + 2 + 2)) { // Corrected calculation: 2 (header) + 1 (length byte) + length (payload, including protocol) + 2 (checksum) + 2 (tail)
                throw new ProtocolException(String.format(
                        "Packet length mismatch. Declared: %d, actual: %d (expected: %d)",
                        declaredLength, data.length - 5, declaredLength + 5)); //
            }

            // Verify checksum using CRC-16/X25
            // Checksum is calculated over the data from the length byte (index 2) up to the byte before checksum.
            int receivedChecksum = ((data[data.length - 4] & 0xFF) << 8) | (data[data.length - 3] & 0xFF);
            ByteBuffer checksumBuffer = ByteBuffer.wrap(data, 2, data.length - 6);
            int calculatedChecksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

            if (receivedChecksum != calculatedChecksum) {
                throw new ProtocolException(String.format(
                        "Checksum mismatch (received: 0x%04X, calculated: 0x%04X, raw: %s)",
                        receivedChecksum, calculatedChecksum, Hex.encodeHexString(data)));
            }

        } else if (header1 == PROTOCOL_HEADER_79_1 && header2 == PROTOCOL_HEADER_79_2) { // 0x7979 header
            // For 0x7979 packets, data[2] is the protocol type. Length is determined by finding 0x0D0A.
            // Checksum calculation for 0x7979: often from data[2] (protocol type) to data[length-5] (before checksum and footer).
            int receivedChecksum = ((data[data.length - 4] & 0xFF) << 8) | (data[data.length - 3] & 0xFF);
            ByteBuffer checksumBuffer = ByteBuffer.wrap(data, 2, data.length - 6); // Data from protocol type to before checksum
            int calculatedChecksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);
            if (receivedChecksum != calculatedChecksum) {
                throw new ProtocolException(String.format(
                        "Checksum mismatch for 0x7979 packet (received: 0x%04X, calculated: 0x%04X, raw: %s)",
                        receivedChecksum, calculatedChecksum, Hex.encodeHexString(data)));
            }
        }
        // For login packets, specifically check its length, if it's 0x7878 and 0x01 protocol
        if (header1 == PROTOCOL_HEADER_1 && header2 == PROTOCOL_HEADER_2 && data[3] == PROTOCOL_LOGIN && data.length != LOGIN_PACKET_LENGTH) {
            throw new ProtocolException("Invalid login packet length: " + data.length + ", expected: " + LOGIN_PACKET_LENGTH);
        }
    }

    private DeviceMessage handleGps(ByteBuffer buffer, DeviceMessage message,
                                    Map<String, Object> parsedData, Variant variant) throws Exception {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        Position position = parseGpsData(buffer);
        parsedData.put("position", position);

        // For GPS, the serial number is typically the last two bytes of the data unit,
        // which would be consumed by parseGpsData if it reads the entire payload.
        // If it doesn't, we need to extract it here. For simplicity, assume serial number 0 for now.
        // The protocol documentation specifies the serial number is in the information content,
        // often at the end of the data unit.
        short serialNumberForResponse = 0; // Default or extract from data if needed

        byte[] response = generateStandardResponse(PROTOCOL_GPS, serialNumberForResponse, (byte)0x01);
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

        // The timestamp is 6 bytes
        position.setTimestamp(LocalDateTime.of(
                2000 + (buffer.get() & 0xFF),
                buffer.get() & 0xFF,
                buffer.get() & 0xFF,
                buffer.get() & 0xFF,
                buffer.get() & 0xFF,
                buffer.get() & 0xFF));

        // Satellites (1 byte)
        position.setSatellites(buffer.get() & 0xFF);

        // Latitude and Longitude (4 bytes each, signed int)
        // GT06 format: raw_value / 1,800,000.0 to get decimal degrees
        double latitude = buffer.getInt() / 1800000.0;
        double longitude = buffer.getInt() / 1800000.0;
        position.setLatitude(latitude);
        position.setLongitude(longitude);

        // Speed (1 byte, km/h) converted to knots if needed (x 1.852)
        position.setSpeed((buffer.get() & 0xFF) * 1.852);

        // Course and status (2 bytes). This might need to be parsed more carefully.
        // For simple GPS packets, it usually just contains course.
        position.setCourse((double) (buffer.getShort() & 0xFFFF));

        // If there are more fields like LBS, ACC status, etc., they would be parsed here.
        // For basic GT06 GPS packet (0x12), there might be just timestamp, satellites, lat, lon, speed, course.
        // Validity and ignition status are usually part of a "status" byte or word.
        // The original `handleGpsExtended` had logic for this, which might be applicable to basic GPS as well if the format is similar.
        // Assuming a simpler structure for PROTOCOL_GPS (0x12) for now.

        // For validity, in simple GPS, sometimes it's implied if coordinates are not (0,0)
        // or a specific bit in the course/status word.
        // For now, setting to true, but this might need refinement based on exact 0x12 payload spec.
        position.setValid(true);

        // If there's an I/O status field following standard GPS, parse it here.
        // In some basic GT06 GPS packets, there might be a simple status byte after course.
        // Example: 0x1A - Terminal information content (Voltage, GSM Signal, Alarm, Language)
        if (buffer.remaining() >= 1) { // Check if there's enough data for potential I/O status byte
            int ioStatus = buffer.get() & 0xFF;
            // You might want to parse specific bits from ioStatus for ignition, etc.
            // For example, if bit 0 is ignition status
            position.setIgnition((ioStatus & 0x01) != 0); // Placeholder: assuming bit 0 means ignition
        }


        return position;
    }

    private DeviceMessage handleHeartbeat(ByteBuffer buffer, DeviceMessage message,
                                          Map<String, Object> parsedData) throws Exception {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        // For heartbeat (0x13 or 0x8A), the data payload is typically very small.
        // It might contain device info, voltage, or GSM signal.
        // Example: 0x13 payload often contains Voltage (1 byte), GSM Signal (1 byte), Alarm (1 byte), Language (1 byte)
        if (buffer.remaining() >= 1) {
            parsedData.put("voltage", buffer.get() & 0xFF);
        }
        if (buffer.remaining() >= 1) {
            parsedData.put("gsmSignal", buffer.get() & 0xFF);
        }
        if (buffer.remaining() >= 1) {
            parsedData.put("alarmState", buffer.get() & 0xFF);
        }
        if (buffer.remaining() >= 1) {
            parsedData.put("language", buffer.get() & 0xFF);
        }

        // The serial number for the response is typically the last two bytes of the data unit.
        // In the 0x8A example provided: 7878058a0001fc960d0a
        // length = 05, protocol = 8A, serial = 0001, status = FC
        // The serial number for heartbeat is 0001. So, extract it from the buffer.
        short serialNumber = 0;
        if (buffer.remaining() >= 2) {
            // Adjust position if you've read other fields.
            // The serial number should be the last two bytes before the CRC/tail.
            // If the buffer already points to the serial number, just read it.
            serialNumber = buffer.getShort();
            parsedData.put("serialNumber", serialNumber);
        }


        byte[] response = generateStandardResponse(PROTOCOL_HEARTBEAT, serialNumber, (byte)0x01);
        parsedData.put("response", response);

        message.setImei(imei);
        message.setMessageType("HEARTBEAT");
        //acknowledgementHandler.write(null, new AcknowledgementHandler.EventHandled(response), null);

        // Create a non-null collection for the acknowledgement
        Collection<Object> ackObjects = Collections.singletonList(response);
        acknowledgementHandler.write(null, new AcknowledgementHandler.EventDecoded(ackObjects), null);

        return message;
    }

    private DeviceMessage handleAlarm(ByteBuffer buffer, DeviceMessage message,
                                      Map<String, Object> parsedData, Variant variant) throws Exception {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI from previous login");
        }

        Position position = parseGpsData(buffer); // Alarm packets typically contain GPS data first
        position.setAlarmType(extractAlarmType(buffer, variant)); // Alarm type follows GPS data

        // Alarm packets also typically contain a serial number at the end
        short serialNumber = 0;
        if (buffer.remaining() >= 2) {
            serialNumber = buffer.getShort();
            parsedData.put("serialNumber", serialNumber);
        }

        byte[] response = variant == Variant.VL03 ?
                generateVl03AlarmResponse() :
                generateStandardResponse(PROTOCOL_ALARM, serialNumber, (byte)0x01); // Use actual serial

        parsedData.put("response", response);
        parsedData.put("position", position);

        message.setImei(imei);
        message.setMessageType("ALARM");
        acknowledgementHandler.write(null, new AcknowledgementHandler.EventHandled(response), null);

        return message;
    }

    private String extractAlarmType(ByteBuffer buffer, Variant variant) {
        if (buffer.remaining() < 1) { // Ensure there's at least one byte for alarm code
            return "UNKNOWN_ALARM_INSUFFICIENT_DATA";
        }
        int alarmCode = buffer.get() & 0xFF;

        if (variant == Variant.VL03) {
            switch (alarmCode) {
                case 0xA0: return "VL03_HARD_ACCELERATION";
                case 0xA1: return "VL03_HARD_BRAKING";
                case 0xA2: return "VL03_CRASH_DETECTION";
                case 0xA3: return "VL03_TOW_ALARM";
                case 0xA4: return "VL03_JAMMING_DETECTION";
                case 0xA5: return "VL03_FATIGUE_DRIVING";
                default: return "VL03_UNKNOWN_ALARM_" + String.format("%02X", alarmCode);
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
            default: return "UNKNOWN_ALARM_" + String.format("%02X", alarmCode);
        }
    }

    private DeviceMessage handleVl03Extended(ByteBuffer buffer, DeviceMessage message,
                                             Map<String, Object> parsedData) throws Exception {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI for VL03 extended message");
        }

        // VL03 extended packets usually start with an extension type, then GPS data.
        int extensionType = buffer.get() & 0xFF;
        parsedData.put("vl03ExtensionType", extensionType);

        Position position = parseVl03GpsData(buffer); // Parse GPS data part of VL03 extended

        // VL03 extended packets might also have a serial number.
        short serialNumber = 0;
        if (buffer.remaining() >= 2) {
            serialNumber = buffer.getShort();
            parsedData.put("serialNumber", serialNumber);
        }


        byte[] response = generateVl03Response(extensionType, serialNumber);
        parsedData.put("response", response);
        parsedData.put("position", position);

        message.setImei(imei);
        message.setMessageType("VL03_EXTENDED");
        // Acknowledge VL03 extended packets if required
        acknowledgementHandler.write(null, new AcknowledgementHandler.EventHandled(response), null);
        return message;
    }

    private Position parseVl03GpsData(ByteBuffer buffer) {
        Position position = parseGpsData(buffer); // Re-use standard GPS parsing for common fields
        // Add VL03-specific parsing here if there are additional fields in VL03 extended GPS data
        return position;
    }

    private byte[] generateVl03Response(int extensionType, short serialNumber) {
        // VL03 extended response: 78 78 Length (07) Protocol (26) Type (extensionType) Info (Serial) Checksum (2) 0D 0A
        ByteBuffer buf = ByteBuffer.allocate(12)
                .put(PROTOCOL_HEADER_1)
                .put(PROTOCOL_HEADER_2)
                .put((byte)0x07) // Length: 1 (protocol) + 1 (type) + 2 (serial) + 2 (checksum) + 1 (0D) + 1 (0A) = 7
                .put(VL03_PROTOCOL_EXTENDED)
                .put((byte)extensionType)
                .putShort(serialNumber); // Use the actual serial number from the packet

        byte[] dataForChecksum = new byte[7]; // Length (1) + Protocol (1) + Type (1) + Serial (2) + Checksum (2) = 7
        System.arraycopy(buf.array(), 2, dataForChecksum, 0, 5); // Copy from length byte (index 2) to serial number (5 bytes)

        int checksum = Checksum.crc16(Checksum.CRC16_X25, ByteBuffer.wrap(dataForChecksum, 0, 5)); // CRC on Length, Protocol, Type, Serial

        ByteBuffer finalResponse = ByteBuffer.allocate(12)
                .put(buf.array(), 0, 7) // Copy header, length, protocol, type, serial
                .putShort((short)checksum)
                .put((byte)0x0D)
                .put((byte)0x0A);
        return finalResponse.array();
    }

    private byte[] generateVl03AlarmResponse() {
        byte[] response = new byte[14]; // Updated size for VL03 alarm response
        response[0] = PROTOCOL_HEADER_1;
        response[1] = PROTOCOL_HEADER_2;
        response[2] = 0x0B; // Length for VL03 alarm response (Protocol, Status, Time, Checksum)
        response[3] = PROTOCOL_ALARM;
        response[4] = 0x01; // Status: success
        response[5] = 0x00; // Additional status byte, often 0x00
        // No serial number explicitly mentioned in the original generation for VL03 alarm response.
        // If it's present, it should be placed here.
        // For now, let's assume it follows the status byte as per some VL03 docs, or use a default.

        LocalDateTime now = LocalDateTime.now();
        response[6] = (byte)(now.getYear() - 2000); // Year
        response[7] = (byte)now.getMonthValue(); // Month
        response[8] = (byte)now.getDayOfMonth(); // Day
        response[9] = (byte)now.getHour(); // Hour
        response[10] = (byte)now.getMinute(); // Minute
        response[11] = (byte)now.getSecond(); // Second

        // Checksum calculation: from length byte (index 2) to the last byte before checksum
        // which is second byte (index 11). So, length is 11 - 2 + 1 = 10 bytes for CRC.
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 10);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[12] = (byte)(checksum >> 8);
        response[13] = (byte)(checksum & 0xFF);
        // Add termination bytes, increasing array size further
        byte[] finalResponse = Arrays.copyOf(response, 16); // 14 bytes + 2 for termination
        finalResponse[14] = 0x0D;
        finalResponse[15] = 0x0A;

        return finalResponse;
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
        response[2] = 0x05; // Length of payload (protocol + serial + status)
        response[3] = protocol;
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber);
        response[6] = status;

        // Checksum calculation: from length byte (index 2) to status byte (index 6)
        // This is 5 bytes: 0x05, protocol, serialHigh, serialLow, status
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 5);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[7] = (byte)(checksum >> 8);
        response[8] = (byte)(checksum);
        response[9] = 0x0A; // The termination byte is 0x0D 0x0A. The original code only put 0x0A.
        // This might be a discrepancy in GT06 implementations.
        // If 0x0D 0x0A are always required, the array size and placement need adjustment.
        // Assuming 0x0D 0x0A for now, so response size should be 12.
        byte[] finalResponse = Arrays.copyOf(response, 12);
        finalResponse[9] = 0x0D;
        finalResponse[10] = 0x0A;
        // The original code was missing 0x0D for standard response. Correcting this.
        return finalResponse;
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
            buffer.position(3); // Skip header (2 bytes) and length (1 byte)
            byte protocol = buffer.get();

            if (protocol == PROTOCOL_GPS || protocol == PROTOCOL_ALARM) {
                // For a raw message, if it's just GPS data, ensure the buffer is correctly positioned
                // to start parsing GPS fields. If it's a full packet, handle it through `handle` method.
                // This method is for parsing *just* the position data from a raw message.
                // Assuming rawMessage starts with the data part relevant to GPS after typical headers.
                // A better approach might be to call handle(rawMessage) and extract position.
                // For now, re-using parseGpsData but ensure its preconditions on buffer are met.
                return parseGpsData(buffer);
            } else if (protocol == PROTOCOL_GPS_EXTENDED) { // Assuming a constant for 0xA0
                // For extended GPS (0xA0), it might have different structure.
                // Re-use parseGpsData and then parse other extended fields if applicable.
                // Or create a dedicated parseGpsExtendedData method.
                return parseGpsData(buffer); // For simplicity, assuming parseGpsData can handle initial parts
            }
        } catch (Exception e) {
            logger.error("Error parsing position from raw message", e);
        }
        return null;
    }

    @Override
    public byte[] generateResponse(Position position) {
        // This method needs context for what type of response to generate.
        // For a generic position, a login response might not be appropriate.
        // It should likely generate a standard acknowledgment or a specific message.
        // For now, retaining original behavior, but it's logically inconsistent.
        return generateStandardResponse(PROTOCOL_LOGIN, (short)0, (byte)0x01);
    }

    private LocalDateTime readDateTime(ByteBuffer buffer) {
        int year = (buffer.get() & 0xFF) + 2000;
        int month = buffer.get() & 0xFF;
        int day = buffer.get() & 0xFF;
        int hour = buffer.get() & 0xFF;
        int minute = buffer.get() & 0xFF;
        int second = buffer.get() & 0xFF;
        return LocalDateTime.of(year, month, day, hour, minute, second);
    }

    // New handleInfoReport method
    private DeviceMessage handleInfoReport(ByteBuffer buffer, DeviceMessage message,
                                           Map<String, Object> parsedData, Variant variant,
                                           ChannelHandlerContext ctx) throws Exception {
        String imei = lastValidImei.get();
        if (imei == null) {
            throw new ProtocolException("No valid IMEI from previous login for info report");
        }

        // After protocol byte (0x01), the payload starts.
        // In example: 579404414c4d313d44353b...
        // The '57' is likely a command or information identifier.
        // The rest is ASCII data.

        // Read the command/sub-type
        byte commandType = buffer.get(); // This will be 0x57 for the example
        parsedData.put("commandType", commandType);

        // Read the rest of the payload as ASCII string until before serial/checksum/footer
        // The entire packet is `data` byte array.
        // ASCII data starts after 0x79 0x79 0x01 0x57 (4 bytes). So, at index 4.
        // ASCII data ends before 0x00 0x02 0xeb 0x9c 0x0d 0x0a (6 bytes from end).
        // So, ASCII data length = data.length - 4 (start) - 6 (end) = data.length - 10.

        int contentStart = buffer.position(); // This is where ASCII data starts (after 0x57)
        int contentEnd = buffer.limit() - 6; // Before serial (0002) and checksum (eb9c) and footer (0d0a)
        int lengthToRead = contentEnd - contentStart;

        if (lengthToRead < 0) {
            throw new ProtocolException("Error parsing 0x7979 info report: Negative content length.");
        }
        byte[] asciiBytes = new byte[lengthToRead];
        buffer.get(asciiBytes);

        String infoContent = new String(asciiBytes, java.nio.charset.StandardCharsets.US_ASCII);
        parsedData.put("infoContentRaw", infoContent);

        // Parse key-value pairs
        parseKeyValuePairs(infoContent, parsedData);

        // Read serial number (2 bytes)
        short serialNumber = buffer.getShort();
        parsedData.put("serialNumber", serialNumber);
        message.setSerialNumber(serialNumber);

        message.setMessageType("INFO_REPORT");
        message.setImei(imei);
        parsedData.put("deviceId", generateDeviceId(message.getImei()));

        // Generate response for info report (often a simple ACK with serial)
        // For 0x7979 info report, typical response is 7979 length protocol serial checksum 0D0A
        // Length: 1 (Protocol) + 2 (Serial) + 1 (Status) = 4 bytes.
        byte[] response = generate7979Response(PROTOCOL_INFO_REPORT, serialNumber, (byte)0x01); // 0x01 success
        message.setResponseData(response);
        message.setResponseRequired(true);

        logger.info("Processed info report for IMEI: {}", imei);
        return message;
    }

    // New helper method to parse key-value pairs
    private void parseKeyValuePairs(String content, Map<String, Object> parsedData) {
        String[] pairs = content.split(";");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                parsedData.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
    }

    // New generate7979Response method
    private byte[] generate7979Response(byte protocolType, short serialNumber, byte status) {
        // 79 79 Length Protocol Serial Status Checksum 0D 0A
        // Length = 1 (Protocol) + 2 (Serial) + 1 (Status) = 4 bytes
        byte[] response = new byte[11]; // 2 header + 1 length + 1 protocol + 2 serial + 1 status + 2 checksum + 2 footer

        response[0] = PROTOCOL_HEADER_79_1;
        response[1] = PROTOCOL_HEADER_79_2;
        response[2] = 0x04; // Length of payload: protocol + serial + status
        response[3] = protocolType;
        response[4] = (byte)(serialNumber >> 8);
        response[5] = (byte)(serialNumber & 0xFF);
        response[6] = status; // 0x01 for success

        // Checksum calculation: from length byte (index 2) to status byte (index 6)
        // So, 0x04, protocolType, serialHigh, serialLow, status (5 bytes)
        ByteBuffer checksumBuffer = ByteBuffer.wrap(response, 2, 5);
        int checksum = Checksum.crc16(Checksum.CRC16_X25, checksumBuffer);

        response[7] = (byte)(checksum >> 8);
        response[8] = (byte)(checksum & 0xFF);
        response[9] = 0x0D;
        response[10] = 0x0A;

        return response;
    }

}