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
public class Tk103Handler implements ProtocolHandler {
    private static final Logger logger = LoggerFactory.getLogger(Tk103Handler.class);
    private static final String PROTOCOL_NAME = "TK103";

    private static final Pattern LOGIN_PATTERN = Pattern.compile("^##(.+?),A1;$");
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "^(\\d{15}),(\\d{6}),A,(\\d+\\.\\d+),([NS]),(\\d+\\.\\d+),([EW]),(\\d+),(\\d+),(\\d{6})"
    );

    @Autowired
    private DeviceRepository deviceRepository;

    // Track last processed message to prevent duplicates
    private String lastProcessedMessage;

    @Override
    public boolean supports(String protocolType) {
        return PROTOCOL_NAME.equalsIgnoreCase(protocolType);
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        String message = new String(data, StandardCharsets.US_ASCII).trim();

        // Skip duplicate messages
        if (message.equals(lastProcessedMessage)) {
            logger.debug("Skipping duplicate TK103 message");
            return null;
        }

        lastProcessedMessage = message;
        logger.debug("Processing TK103 message: {}", message);

        try {
            if (LOGIN_PATTERN.matcher(message).matches()) {
                return handleLoginMessage(message, data);
            }

            if (LOCATION_PATTERN.matcher(message).matches()) {
                return handleLocationMessage(message, data);
            }

            throw new ProtocolException("Unsupported message format");
        } catch (Exception e) {
            throw new ProtocolException("Failed to parse TK103 message", e);
        }
    }

    private DeviceMessage handleLoginMessage(String message, byte[] rawData) {
        String imei = message.substring(2, message.indexOf(','));
        logger.info("TK103 login from IMEI: {}", imei);

        Device device = getOrCreateDevice(imei);

        DeviceMessage deviceMessage = new DeviceMessage();
        deviceMessage.setProtocolType(PROTOCOL_NAME);
        deviceMessage.setMessageType(DeviceMessage.TYPE_LOGIN);
        deviceMessage.setImei(imei);
        deviceMessage.setRawData(rawData);
        deviceMessage.addParsedData("response", "LOAD".getBytes(StandardCharsets.US_ASCII));
        deviceMessage.addParsedData("device", device);

        return deviceMessage;
    }

    private DeviceMessage handleLocationMessage(String message, byte[] rawData) throws ProtocolException {
        String[] parts = message.split(",");
        if (parts.length < 10) {
            throw new ProtocolException("Invalid location message format");
        }

        String imei = parts[0];
        Device device = getOrCreateDevice(imei);

        Position position = new Position();
        position.setDevice(device);

        try {
            // Parse coordinates (NMEA format: DDMM.MMMM)
            position.setLatitude(parseNmeaCoordinate(parts[3], parts[4]));
            position.setLongitude(parseNmeaCoordinate(parts[5], parts[6]));

            // Parse speed (convert knots to km/h)
            position.setSpeed(Double.parseDouble(parts[7]) * 1.852);

            // Parse course
            position.setCourse(Double.parseDouble(parts[8]));

            // Parse timestamp (ddMMyy + HHmmss)
            position.setTimestamp(parseDateTime(parts[9], parts[1]));
            position.setValid("A".equals(parts[2]));

            DeviceMessage deviceMessage = new DeviceMessage();
            deviceMessage.setProtocolType(PROTOCOL_NAME);
            deviceMessage.setMessageType(DeviceMessage.TYPE_LOCATION);
            deviceMessage.setImei(imei);
            deviceMessage.setRawData(rawData);
            deviceMessage.addParsedData("position", position);
            deviceMessage.addParsedData("response", "ON".getBytes(StandardCharsets.US_ASCII));
            deviceMessage.addParsedData("device", device);

            return deviceMessage;
        } catch (Exception e) {
            throw new ProtocolException("Failed to parse location data", e);
        }
    }

    private Device getOrCreateDevice(String imei) {
        Optional<Device> existingDevice = deviceRepository.findByImei(imei);
        if (existingDevice.isPresent()) {
            return existingDevice.get();
        }

        Device newDevice = new Device();
        newDevice.setImei(imei);
        newDevice.setName("TK103-" + imei);
        newDevice.setProtocolType(PROTOCOL_NAME);
        return deviceRepository.save(newDevice);
    }

    private double parseNmeaCoordinate(String value, String hemisphere) {
        double degrees = Double.parseDouble(value.substring(0, value.indexOf('.') - 2));
        double minutes = Double.parseDouble(value.substring(value.indexOf('.') - 2));
        double decimal = degrees + (minutes / 60);
        return ("S".equals(hemisphere) || "W".equals(hemisphere)) ? -decimal : decimal;
    }

    private LocalDateTime parseDateTime(String date, String time) {
        return LocalDateTime.parse(
                date + time,
                DateTimeFormatter.ofPattern("ddMMyyHHmmss")
        );
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        return supports(protocol);
    }

    @Override
    public Position parsePosition(byte[] rawMessage) {
        try {
            DeviceMessage message = handle(rawMessage);
            return (Position) message.getParsedData().get("position");
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public byte[] generateResponse(Position position) {
        return position != null ? "ON".getBytes(StandardCharsets.US_ASCII)
                : "LOAD".getBytes(StandardCharsets.US_ASCII);
    }
}