package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Component
public class Tk103LoginHandler implements ProtocolHandler {
    private static final Logger logger = LoggerFactory.getLogger(Tk103LoginHandler.class);
    private static final Pattern LOGIN_PATTERN = Pattern.compile("^##(.+?),A1;$");
    private static final String PROTOCOL_TYPE = "TK103";
    private static final String MESSAGE_TYPE = DeviceMessage.TYPE_LOGIN;

    @Override
    public boolean supports(String protocolType) {
        return PROTOCOL_TYPE.equalsIgnoreCase(protocolType);
    }

    @Override
    public DeviceMessage handle(byte[] data) throws ProtocolException {
        try {
            String message = new String(data, StandardCharsets.US_ASCII).trim();
            logger.debug("Processing TK103 login message: {}", message);

            if (!LOGIN_PATTERN.matcher(message).matches()) {
                throw new ProtocolException("Invalid TK103 login message format");
            }

            String imei = message.substring(2, message.indexOf(','));
            logger.info("TK103 login from IMEI: {}", imei);

            DeviceMessage deviceMessage = new DeviceMessage();
            deviceMessage.setProtocolType(PROTOCOL_TYPE);
            deviceMessage.setMessageType(MESSAGE_TYPE);
            deviceMessage.setImei(imei);
            deviceMessage.setRawData(data);
            deviceMessage.addParsedData("response", "LOAD".getBytes(StandardCharsets.US_ASCII));

            return deviceMessage;
        } catch (Exception e) {
            throw new ProtocolException("Failed to process TK103 login message: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean canHandle(String protocol, String version) {
        return supports(protocol) && MESSAGE_TYPE.equals(DeviceMessage.TYPE_LOGIN);
    }

    @Override
    public Position parsePosition(byte[] rawMessage) {
        return null; // Login handler doesn't process positions
    }

    @Override
    public byte[] generateResponse(Position position) {
        return "LOAD".getBytes(StandardCharsets.US_ASCII);
    }
}