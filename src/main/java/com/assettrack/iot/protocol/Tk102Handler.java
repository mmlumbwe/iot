package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import org.apache.coyote.ProtocolException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class Tk102Handler implements ProtocolHandler {
    // TK102 protocol pattern (updated to match actual TK102 format)
    private static final Pattern PATTERN = Pattern.compile(
            "\\[(\\d{2})(\\d{2})(\\d{2})([AV])(\\d{2})(\\d{2}\\.\\d{4})([NS])(\\d{3})(\\d{2}\\.\\d{4})([EW])(\\d{3}\\.\\d{3})(\\d{2})(\\d{2})(\\d{2})\\]");

    @Override
    public Position parsePosition(byte[] rawMessage) {
        if (rawMessage == null || rawMessage.length == 0) {
            return null;
        }

        String message = new String(rawMessage, StandardCharsets.UTF_8).trim();
        Matcher matcher = PATTERN.matcher(message);

        if (!matcher.matches()) {
            return null;
        }

        Position position = new Position();
        Device device = new Device();

        try {
            // Parse time (HHMMSS)
            /*int hour = Integer.parseInt(matcher.group(1));
            int minute = Integer.parseInt(matcher.group(2));
            int second = Integer.parseInt(matcher.group(3));*/

            // Parse validity
            boolean valid = "A".equals(matcher.group(4));
            position.setValid(valid);

            // Parse latitude (DDMM.MMMM)
            double latDegrees = Double.parseDouble(matcher.group(5));
            double latMinutes = Double.parseDouble(matcher.group(6));
            double latitude = latDegrees + latMinutes / 60;
            if ("S".equals(matcher.group(7))) {
                latitude = -latitude;
            }
            position.setLatitude(latitude);

            // Parse longitude (DDDMM.MMMM)
            double lonDegrees = Double.parseDouble(matcher.group(8));
            double lonMinutes = Double.parseDouble(matcher.group(9));
            double longitude = lonDegrees + lonMinutes / 60;
            if ("W".equals(matcher.group(10))) {
                longitude = -longitude;
            }
            position.setLongitude(longitude);

            // Parse speed (knots)
            double speedKnots = Double.parseDouble(matcher.group(11));
            position.setSpeed(speedKnots * 1.852); // Convert to km/h

            // Parse date (DDMMYY)
            /*int day = Integer.parseInt(matcher.group(12));
            int month = Integer.parseInt(matcher.group(13));
            int year = 2000 + Integer.parseInt(matcher.group(14));

            // Create timestamp (Note: Date constructor is deprecated, used here for simplicity)
            @SuppressWarnings("deprecation")
            Date timestamp = new Date(year - 1900, month - 1, day, hour, minute, second);
            position.setTimestamp(timestamp);*/
            int day = Integer.parseInt(matcher.group(12));
            int month = Integer.parseInt(matcher.group(13));
            int year = 2000 + Integer.parseInt(matcher.group(14));
            int hour = Integer.parseInt(matcher.group(9));
            int minute = Integer.parseInt(matcher.group(10));
            int second = Integer.parseInt(matcher.group(11));

            // Create LocalDateTime directly
            LocalDateTime timestamp = LocalDateTime.of(
                    year,        // Full year (e.g., 2023)
                    month,       // Month (1-12)
                    day,         // Day (1-31)
                    hour,        // Hour (0-23)
                    minute,      // Minute (0-59)
                    second       // Second (0-59)
            );

            position.setTimestamp(timestamp);

            // Set device info
            device.setProtocolType("TK102");
            position.setDevice(device);

        } catch (Exception e) {
            return null;
        }

        return position;
    }

    @Override
    public byte[] generateResponse(Position position) {
        // Standard TK102 acknowledgment
        return "[ACK]".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean supports(String protocolType) {
        return protocolType != null && protocolType.equalsIgnoreCase("TK102");
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        return supports(protocol);
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("TK102");

        try {
            Position position = parsePosition(data);
            if (position == null) {
                throw new ProtocolException("Invalid TK102 message format");
            }

            message.setMessageType("LOCATION");
            message.addParsedData("position", position);
            message.addParsedData("response", generateResponse(position));

            return message;
        } catch (Exception e) {
            throw new ProtocolException("Failed to handle TK102 message", e);
        }
    }
}