package com.assettrack.iot.protocol;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.repository.DeviceRepository;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class Tk103LocationHandler implements ProtocolHandler {
    private static final Logger logger = LoggerFactory.getLogger(Tk103LocationHandler.class);
    private static final Pattern LOCATION_PATTERN = Pattern.compile("^(.+?),(\\d{6}),A,(\\d+\\.\\d+),([NS]),(\\d+\\.\\d+),([EW]),(\\d+\\.?\\d*),(\\d+\\.?\\d*),(\\d{6}),.*$");

    @Autowired
    private DeviceRepository deviceRepository;

    private byte[] lastResponse;

    @Override
    public boolean supports(String protocolType) {
        return "TK103".equalsIgnoreCase(protocolType);
    }

    @Override
    public Position parsePosition(byte[] data) throws ProtocolException {
        String message = new String(data, StandardCharsets.US_ASCII).trim();
        if (!message.contains("GPRMC") && !message.contains("GPGGA")) {
            throw new ProtocolException("Not a TK103 location message");
        }

        String[] parts = message.split(",");
        if (parts.length < 10) {
            throw new ProtocolException("Invalid TK103 location message");
        }

        String imei = parts[0];
        Optional<Device> device = deviceRepository.findByImei(imei);
        if (device.isEmpty()) {
            throw new ProtocolException("Unknown device IMEI: " + imei);
        }

        Position position = new Position();
        position.setDevice(device.get());
        position.setLatitude(parseNmeaCoordinate(parts[3], parts[4]));
        position.setLongitude(parseNmeaCoordinate(parts[5], parts[6]));
        position.setSpeed(Double.parseDouble(parts[7]) * 1.852);
        position.setCourse(Double.parseDouble(parts[8]));
        position.setTimestamp(parseDateTime(parts[9], parts[1]));
        position.setValid("A".equals(parts[2]));

        this.lastResponse = "ON".getBytes(StandardCharsets.US_ASCII);
        return position;
    }

    @Override
    public byte[] generateResponse(Position position) {
        return lastResponse;
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        DeviceMessage message = new DeviceMessage();
        try {
            Position position = parsePosition(data);
            message.setProtocolType("TK103");
            message.setRawData(data);
            message.addParsedData("position", position);
            message.addParsedData("response", lastResponse);
            return message;
        } catch (ProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new ProtocolException("Failed to handle TK103 location message", e);
        }
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        return supports(protocol);
    }

    private double parseNmeaCoordinate(String value, String hemisphere) {
        double degrees = Double.parseDouble(value.substring(0, 2));
        double minutes = Double.parseDouble(value.substring(2));
        double decimal = degrees + (minutes / 60);
        return ("S".equals(hemisphere) || "W".equals(hemisphere)) ? -decimal : decimal;
    }

    private LocalDateTime parseDateTime(String date, String time) {
        return LocalDateTime.parse(
                date + time,
                DateTimeFormatter.ofPattern("ddMMyyHHmmss")
        );
    }
}
