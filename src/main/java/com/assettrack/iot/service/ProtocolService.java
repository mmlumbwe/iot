package com.assettrack.iot.service;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.protocol.Gt06Handler;
import com.assettrack.iot.protocol.ProtocolHandler;
import com.assettrack.iot.protocol.TeltonikaHandler;
import com.assettrack.iot.protocol.Tk103Handler;
import com.assettrack.iot.security.AuthService;
import com.assettrack.iot.security.PayloadValidator;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
public class ProtocolService {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolService.class);

    private final AuthService authService;
    private final PayloadValidator validator;
    private final DeviceStatusService deviceStatusService;
    private final PositionService positionService;
    private final Gt06Handler gt06Handler;
    private final Tk103Handler tk103Handler;
    private final TeltonikaHandler teltonikaHandler;

    @Autowired
    public ProtocolService(AuthService authService,
                           PayloadValidator validator,
                           DeviceStatusService deviceStatusService,
                           PositionService positionService,
                           Gt06Handler gt06Handler,
                           Tk103Handler tk103Handler,
                           TeltonikaHandler teltonikaHandler) {
        this.authService = authService;
        this.validator = validator;
        this.deviceStatusService = deviceStatusService;
        this.positionService = positionService;
        this.gt06Handler = gt06Handler;
        this.tk103Handler = tk103Handler;
        this.teltonikaHandler = teltonikaHandler;
    }

    // If you need to maintain compatibility with DeviceMessage in ProtocolService:
    /*public DeviceMessage convertToDeviceMessage(Position position, byte[] response) {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol("TK103");
        message.setMessageType(position == null ? "LOGIN" : "LOCATION");
        if (position != null) {
            message.setImei(position.getDevice().getImei());
        }

        Map<String, Object> parsedData = new HashMap<>();
        if (position != null) {
            parsedData.put("position", position);
        }
        parsedData.put("response", response);
        message.setParsedData(parsedData);

        return message;
    }*/

    private String determineProtocolVersion(String protocol, byte[] data) {
        if ("GT06".equalsIgnoreCase(protocol)) {
            if (data.length >= 4 && data[0] == 0x78 && data[1] == 0x78) {
                switch (data[3]) {  // Protocol number byte
                    case 0x01: return "LOGIN";
                    case 0x12: return "DATA";
                    case 0x13: return "HEARTBEAT";
                    // Add other GT06 protocol numbers
                }
            }
        }
        return "UNKNOWN";
    }

    private String extractImei(String protocolType, byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return "UNKNOWN";
        }

        try {
            switch (protocolType.toUpperCase()) {
                case "GT06":
                    return extractGt06Imei(rawData);
                case "TK103":
                    return extractTk103Imei(rawData);
                case "TELTONIKA":
                    return extractTeltonikaImei(rawData);
                default:
                    logger.warn("Unsupported protocol for IMEI extraction: {}", protocolType);
                    return "UNKNOWN";
            }
        } catch (Exception e) {
            logger.error("IMEI extraction failed for protocol {}: {}", protocolType, e.getMessage());
            return "UNKNOWN";
        }
    }

    private String extractGt06Imei(byte[] rawData) throws ProtocolException {
        // Validate packet length (GT06 login packet is exactly 28 bytes)
        if (rawData == null || rawData.length < 28) {
            throw new ProtocolException("Invalid GT06 packet length: " + (rawData == null ? "null" : rawData.length));
        }

        // Verify packet start marker
        if (rawData[0] != 0x78 || rawData[1] != 0x78) {
            throw new ProtocolException("Invalid GT06 packet header");
        }

        StringBuilder imei = new StringBuilder(15);  // Pre-allocate for 15 digits

        // Extract first 14 digits from bytes 4-10 (7 bytes)
        for (int i = 4; i <= 10; i++) {
            // High nibble (first digit)
            imei.append((rawData[i] >> 4) & 0x0F);
            // Low nibble (second digit)
            imei.append(rawData[i] & 0x0F);
        }

        // Extract 15th digit from high nibble of byte 11
        imei.append((rawData[11] >> 4) & 0x0F);

        // Verify padding in low nibble of last byte is 0
        /*if ((rawData[11] & 0x0F) != 0) {
            throw new ProtocolException("Invalid IMEI padding in last byte");
        }*/

        String result = imei.toString();
        System.out.println("Intermediate IMEI: " + imei.toString());
        // Final validation
        if (!result.matches("^\\d{15}$")) {
            throw new ProtocolException("Invalid GT06 IMEI format: " + result);
        }
        return result;
    }

    private String extractTk103Imei(byte[] rawData) {
        // TK103 protocol: ASCII format ##IMEI,...
        if (rawData.length < 8 || rawData[0] != '#' || rawData[1] != '#') {
            throw new ProtocolException("Invalid TK103 packet header");
        }

        String message = new String(rawData, StandardCharsets.US_ASCII);
        String[] parts = message.split(",", 3);
        if (parts.length < 1) {
            throw new ProtocolException("No comma found in TK103 message");
        }

        // Remove ## prefix from IMEI
        String imei = parts[0].substring(2);
        if (imei.length() != 15 || !imei.matches("\\d+")) {
            throw new ProtocolException("Invalid TK103 IMEI format: " + imei);
        }
        return imei;
    }

    private String extractTeltonikaImei(byte[] rawData) {
        // Teltonika protocol: First 15 bytes are ASCII IMEI
        if (rawData.length < 15) {
            throw new ProtocolException("Packet too short for Teltonika IMEI");
        }

        String imei = new String(rawData, 0, 15, StandardCharsets.US_ASCII);
        if (imei.length() != 15 || !imei.matches("\\d+")) {
            throw new ProtocolException("Invalid Teltonika IMEI format: " + imei);
        }
        return imei;
    }

    private String detectProtocolVersion(String protocolType, byte[] data) {
        switch (protocolType) {
            case "TELTONIKA":
                if (data.length > 8) {
                    int codecId = data[8] & 0xFF;
                    return codecId == 0x08 ? "CODEC8" : "CODEC16";
                }
                break;
            case "GT06":
                if (data.length > 3) {
                    return data[3] == 0x12 ? "GPS" : "LOGIN";
                }
                break;
        }
        return "1.0";
    }

    /*public byte[] generateResponse(String protocolType, Position position) {
        String version = detectProtocolVersion(protocolType, new byte[0]);
        return handlerFactory.getHandler(protocolType, version)
                .generateResponse(position);
    }*/

    public byte[] generateErrorResponse(String protocolType, ProtocolException error) {
        switch (protocolType.toUpperCase()) {
            case "GT06":
                return new byte[] {0x78, 0x78, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0D, 0x0A};
            default:
                return null;
        }
    }

    public DeviceMessage parseData(String protocolType, byte[] data) {
        try {
            ProtocolHandler handler = getHandlerForProtocol(protocolType);

            // Use the handler's parse method (assuming it exists)
            Position position = handler.parsePosition(data);

            // Generate response and convert to DeviceMessage
            byte[] response = handler.generateResponse(position);
            return convertToDeviceMessage(position, response);

        } catch (Exception e) {
            logger.warn("Failed to parse {} data: {}", protocolType, e.getMessage());
            return createErrorMessage(protocolType, e);
        }
    }

    private ProtocolHandler getHandlerForProtocol(String protocolType) throws ProtocolException {
        switch (protocolType.toUpperCase()) {
            case "GT06":
                return gt06Handler;
            case "TK103":
                return tk103Handler;
            case "TELTONIKA":
                return teltonikaHandler;
            default:
                throw new ProtocolException("Unsupported protocol type: " + protocolType);
        }
    }

    private DeviceMessage convertToDeviceMessage(Position position, byte[] response) {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol(position.getDevice().getProtocolType());
        message.setMessageType(position == null ? "LOGIN" : "LOCATION");
        message.setImei(position.getDevice().getImei());

        Map<String, Object> parsedData = new HashMap<>();
        parsedData.put("position", position);
        parsedData.put("response", response);
        message.setParsedData(parsedData);

        return message;
    }

    private DeviceMessage createErrorMessage(String protocolType, Exception error) {
        DeviceMessage message = new DeviceMessage();
        message.setProtocol(protocolType);
        message.setMessageType("ERROR");
        //message.setError(error.getMessage());

        try {
            // Generate basic error response
            //message.setResponse(getBasicErrorResponse(protocolType));
        } catch (Exception e) {
            logger.error("Failed to generate error response", e);
        }

        return message;
    }

    private byte[] getBasicErrorResponse(String protocolType) {
        switch (protocolType.toUpperCase()) {
            case "GT06":
                return new byte[] {0x78, 0x78, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0D, 0x0A};
            case "TK103":
                return "ERROR".getBytes(StandardCharsets.US_ASCII);
            default:
                return "PROTOCOL_ERROR".getBytes(StandardCharsets.US_ASCII);
        }
    }
}