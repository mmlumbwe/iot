package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import org.apache.coyote.ProtocolException;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.regex.Pattern;

@Component
public class TeltonikaHandler implements ProtocolHandler {
    private static final int CODEC8 = 0x08;
    private static final int CODEC16 = 0x10;
    private static final int IMEI_LENGTH = 15;
    private static final int MIN_MESSAGE_LENGTH = 20;
    private static final Pattern IMEI_PATTERN = Pattern.compile("^\\d{15}$");

    @Override
    public Position parsePosition(byte[] rawMessage) {
        if (rawMessage == null || rawMessage.length < MIN_MESSAGE_LENGTH) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(rawMessage).order(ByteOrder.BIG_ENDIAN);
        Position position = new Position();
        Device device = new Device();

        try {
            // Parse Teltonika header
            buffer.getInt(); // Skip preamble
            buffer.getInt(); // Skip data length
            int codecId = buffer.get() & 0xFF;

            if (codecId == CODEC8 || codecId == CODEC16) {
                // Parse IMEI and set device
                byte[] imeiBytes = new byte[IMEI_LENGTH];
                buffer.get(imeiBytes);
                String rawImei = new String(imeiBytes).trim();
                String cleanedImei = cleanImei(rawImei);

                if (!isValidImei(cleanedImei)) {
                    return null; // Invalid IMEI format
                }

                device.setImei(cleanedImei);
                device.setProtocolType("TELTONIKA");
                position.setDevice(device);

                // Parse GPS data
                /*long timestamp = buffer.getLong();
                if (timestamp <= 0) {
                    return null; // Invalid timestamp
                }
                position.setTimestamp(new Date(timestamp));*/
                int timestampSeconds = buffer.getInt(); // 4-byte Unix timestamp
                if (timestampSeconds <= 0) {
                    return null; // Invalid timestamp
                }

                // Convert to LocalDateTime
                LocalDateTime timestamp = Instant.ofEpochSecond(timestampSeconds)
                        .atZone(ZoneId.systemDefault()) // Or specify your timezone
                        .toLocalDateTime();
                position.setTimestamp(timestamp);

                double latitude = buffer.getInt() / 10000000.0;
                double longitude = buffer.getInt() / 10000000.0;

                if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                    return null; // Invalid coordinates
                }
                position.setLatitude(latitude);
                position.setLongitude(longitude);

                // Convert knots to km/h (Teltonika provides speed in knots)
                double speedKnots = buffer.getShort() & 0xFFFF;
                position.setSpeed(speedKnots * 1.852); // Convert to km/h

                int course = buffer.getShort() & 0xFFFF;
                position.setCourse((double) course);
                position.setValid(true); // Mark as valid if we got this far

                // Parse additional data for CODEC16
                if (codecId == CODEC16) {
                    parseCodec16Data(buffer, position);
                }
            } else {
                return null; // Unsupported codec
            }
        } catch (Exception e) {
            return null; // Invalid message format
        }

        return position;
    }

    private String cleanImei(String rawImei) {
        if (rawImei == null) {
            return "";
        }
        // Remove all non-digit characters
        return rawImei.replaceAll("[^0-9]", "");
    }

    private boolean isValidImei(String imei) {
        return imei != null && IMEI_PATTERN.matcher(imei).matches();
    }

    private void parseCodec16Data(ByteBuffer buffer, Position position) {
        try {
            int eventId = buffer.get() & 0xFF;
            int propertiesCount = buffer.get() & 0xFF;

            StringBuilder attributes = new StringBuilder("{");
            boolean firstAttr = true;

            for (int i = 0; i < propertiesCount; i++) {
                int propertyId = buffer.get() & 0xFF;
                int propertyLength = buffer.get() & 0xFF;

                if (!firstAttr) {
                    attributes.append(",");
                }
                firstAttr = false;

                switch (propertyId) {
                    case 0x01: // DIN1 (Digital Input 1)
                        boolean din1 = buffer.get() == 1;
                        position.setIgnition(din1);
                        attributes.append("\"din1\":").append(din1);
                        break;
                    case 0x02: // DIN2 (Digital Input 2)
                        boolean din2 = buffer.get() == 1;
                        attributes.append("\"din2\":").append(din2);
                        break;
                    case 0x66: // Battery Level
                        int battery = buffer.getShort() & 0xFFFF;
                        if (battery >= 0) {
                            position.setBatteryLevel((double) battery / 1000); // Convert mV to V
                        }
                        attributes.append("\"battery\":").append(battery);
                        break;
                    case 0x21: // Odometer
                        int odometer = buffer.getInt();
                        if (odometer >= 0) {
                            attributes.append("\"odometer\":").append(odometer);
                        } else {
                            attributes.append("\"odometer\":null");
                        }
                        break;
                    // Add more properties as needed
                    default:
                        // Skip unsupported properties
                        for (int j = 0; j < propertyLength; j++) {
                            buffer.get();
                        }
                        attributes.append("\"prop_").append(propertyId)
                                .append("\":\"skipped\"");
                }
            }

            attributes.append("}");
            position.setAttributes(attributes.toString());
        } catch (Exception e) {
            position.setAttributes("{\"error\":\"failed_to_parse_attributes\"}");
        }
    }

    @Override
    public byte[] generateResponse(Position position) {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(1); // Number of accepted data packets (success response)
        return buffer.array();
    }

    @Override
    public boolean supports(String protocolType) {
        return protocolType != null && protocolType.equalsIgnoreCase("TELTONIKA");
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        return supports(protocol);
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("TELTONIKA");

        try {
            Position position = parsePosition(data);
            if (position == null) {
                throw new ProtocolException("Invalid Teltonika message format");
            }

            message.setImei(position.getDevice().getImei());
            message.setMessageType("LOCATION");
            message.addParsedData("position", position);
            message.addParsedData("response", generateResponse(position));

            return message;
        } catch (Exception e) {
            throw new ProtocolException("Failed to handle Teltonika message", e);
        }
    }
}