package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.service.DeviceService;
import com.assettrack.iot.service.PositionService;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.Year;

@Component
public class Gt06LocationHandler {
    private static final Logger logger = LoggerFactory.getLogger(Gt06LocationHandler.class);
    private static final int LOCATION_PROTOCOL_NUMBER = 0x12;
    private static final int MIN_LOCATION_PACKET_SIZE = 35;
    private static final double COORDINATE_DIVISOR = 30000.0;

    private final PositionService positionService;
    private final DeviceService deviceService;
    private String lastLoginImei;

    public Gt06LocationHandler(PositionService positionService, DeviceService deviceService) {
        this.positionService = positionService;
        this.deviceService = deviceService;
    }

    // In Gt06LocationHandler.java
    public void setLastLoginImei(byte[] loginPacket) {
        this.lastLoginImei = new Gt06LoginHandler(null).parseImei(loginPacket);
    }

    public byte[] handle(byte[] data) {
        try {
            if (!isValidLocationPacket(data)) {
                logger.warn("Invalid location packet structure");
                return createBasicAcknowledgement();
            }

            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            buffer.position(3); // Skip header and length

            // Verify protocol number
            int protocolNumber = buffer.get() & 0xFF;
            if (protocolNumber != LOCATION_PROTOCOL_NUMBER) {
                logger.warn("Expected location packet (0x12), got 0x{}",
                        String.format("%02X", protocolNumber));
                return createBasicAcknowledgement();
            }

            if (lastLoginImei == null || lastLoginImei.isEmpty()) {
                logger.error("No IMEI available from login packet");
                return createBasicAcknowledgement();
            }

            Device device = deviceService.findByImei(lastLoginImei);
            if (device == null) {
                logger.error("Device not found for IMEI: {}", lastLoginImei);
                return createBasicAcknowledgement();
            }

            // Parse timestamp (6 bytes)
            LocalDateTime timestamp = parseTimestamp(buffer);

            // Process the location data
            return processLocationData(data, buffer, device, timestamp);

        } catch (Exception e) {
            logger.error("Location packet processing error", e);
            return createBasicAcknowledgement();
        }
    }

    private double parseGt06Coordinate(ByteBuffer buffer) {
        byte[] bytes = new byte[4];
        buffer.get(bytes);

        logger.debug("Coordinate bytes: {}", bytesToHex(bytes));

        int rawValue = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();

        logger.debug("Raw value: {}", rawValue);

        return rawValue / 1800000.0;  // Correct scaling factor for GT06
    }

    private byte[] processLocationData(byte[] data, ByteBuffer buffer, Device device, LocalDateTime timestamp) {
        try {
            Position position = new Position();
            position.setDevice(device);
            position.setProtocol("GT06");
            position.setTimestamp(timestamp);


            // Parse coordinates - NOTE: Longitude comes first in GT06 packets!
            double longitude = parseGt06Coordinate(buffer);  // Actually longitude
            double latitude = parseGt06Coordinate(buffer);   // Actually latitude

            logger.debug("Raw coordinates - Lat: {}, Lon: {}", latitude, longitude);

            // Validate coordinates
            if (!isValidCoordinate(latitude, longitude)) {
                logger.warn("Rejected invalid coordinates - Lat: {}, Lon: {}", latitude, longitude);
                return createBasicAcknowledgement();
            }

            position.setLatitude(latitude);
            position.setLongitude(longitude);

            logger.debug("Full location packet bytes: {}", bytesToHex(data));

            // Parse remaining fields
            position.setSpeed((buffer.get() & 0xFF) * 1.852); // knots to km/h
            position.setCourse((double) (buffer.getShort() & 0xFFFF));
            //position.setAltitude((double) buffer.getShort());
            position.setSatellites(buffer.get() & 0xFF);

            positionService.savePosition(position);

            logger.info("Saved position - Lat: {}, Lon: {}", latitude, longitude);
            return createAcknowledgement(data);

        } catch (Exception e) {
            logger.error("Error processing location data", e);
            return createBasicAcknowledgement();
        }
    }

    private double convertDdmmToDecimal(double ddmm) {
        // Convert from DDMM.MMMMM to decimal degrees
        boolean negative = ddmm < 0;
        ddmm = Math.abs(ddmm);

        int degrees = (int)(ddmm / 100);
        double minutes = ddmm - (degrees * 100);

        double result = degrees + (minutes / 60);
        return negative ? -result : result;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private LocalDateTime parseTimestamp(ByteBuffer buffer) {
        int year = 2000 + (buffer.get() & 0xFF);
        int month = Math.max(1, Math.min(12, buffer.get() & 0xFF));
        int day = Math.max(1, Math.min(Year.of(year).atMonth(month).lengthOfMonth(), buffer.get() & 0xFF));
        int hour = Math.max(0, Math.min(23, buffer.get() & 0xFF));
        int minute = Math.max(0, Math.min(59, buffer.get() & 0xFF));
        int second = Math.max(0, Math.min(59, buffer.get() & 0xFF));

        return LocalDateTime.of(year, month, day, hour, minute, second);
    }

    private boolean isValidLocationPacket(byte[] data) {
        return data != null &&
                data.length >= MIN_LOCATION_PACKET_SIZE &&
                data[0] == 0x78 &&
                data[1] == 0x78 &&
                data[data.length-2] == 0x0D &&
                data[data.length-1] == 0x0A;
    }

    private byte[] createAcknowledgement(byte[] data) {
        return new byte[] {
                0x78, 0x78, 0x05, 0x12,
                data[data.length-5], data[data.length-4], // Serial number
                calculateChecksum(data), 0x0D, 0x0A
        };
    }

    private byte[] createBasicAcknowledgement() {
        return new byte[] {
                0x78, 0x78, 0x05, 0x12, 0x00, 0x01,
                (byte) 0xD9, (byte) 0xDC, 0x0D, 0x0A
        };
    }

    private byte calculateChecksum(byte[] data) {
        byte checksum = 0;
        for (int i = 2; i < data.length - 4; i++) {
            checksum ^= data[i];
        }
        return checksum;
    }
}