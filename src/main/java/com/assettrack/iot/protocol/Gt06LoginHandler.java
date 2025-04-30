package com.assettrack.iot.protocol;

import com.assettrack.iot.model.DeviceMessage;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.protocol.Protocol;
import com.assettrack.iot.protocol.ProtocolHandler;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class Gt06LoginHandler {
    private final Gt06LocationHandler locationHandler;
    private static final Logger logger = LoggerFactory.getLogger(Gt06LoginHandler.class);

    public Gt06LoginHandler(Gt06LocationHandler locationHandler) {
        this.locationHandler = locationHandler;
    }

    public byte[] handle(byte[] data) throws ProtocolException {
        try {
            // Parse IMEI and validate login
            String imei = extractImei(data);
            locationHandler.setLastLoginImei(imei);
            logger.info("Processing GT06 login for IMEI: {}", imei);

            // Generate and return response
            return generateLoginResponse();
        } catch (Exception e) {
            throw new ProtocolException("Login processing failed", e);
        }
    }

    private byte[] generateLoginResponse() {
        // 78 78 05 01 00 01 00 00 00 00 01 0D 0A
        return new byte[] {
                0x78, 0x78, 0x05, 0x01, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00, 0x01, 0x0D, 0x0A
        };
    }

    private String extractImei(byte[] data) {
        // Extract IMEI from bytes 4-11
        StringBuilder imei = new StringBuilder();
        for (int i = 4; i <= 10; i++) {
            imei.append(String.format("%02X", data[i]));
        }
        imei.append(String.format("%01X", data[11] >> 4 & 0x0F));
        return imei.toString();
    }

    private String parseImei(byte[] data) {
        // IMEI parsing logic
        StringBuilder imei = new StringBuilder();
        // First 7 bytes (14 digits)
        for (int i = 4; i <= 10; i++) {
            imei.append((data[i] >> 4) & 0x0F); // High nibble
            imei.append(data[i] & 0x0F);        // Low nibble
        }
        // Last digit from high nibble of byte 11
        imei.append((data[11] >> 4) & 0x0F);
        return imei.toString();
    }
}
