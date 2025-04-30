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
            String imei = parseImei(data);
            logger.info("Processing GT06 login for IMEI: {}", imei);

            // Verify IMEI matches expected value
            if (!"862476051124146".equals(imei)) {
                logger.warn("Unexpected IMEI received: {}", imei);
            }

            // Set the IMEI in the location handler
            locationHandler.setLastLoginImei(data);

            return generateLoginResponse();
        } catch (Exception e) {
            throw new ProtocolException("Login processing failed", e);
        }
    }

    private byte[] generateLoginResponse() {
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

    String parseImei(byte[] data) {
        if (data == null || data.length < 12) {
            throw new IllegalArgumentException("Invalid login packet for IMEI extraction");
        }

        StringBuilder imei = new StringBuilder();
        // Bytes 4-10 contain the IMEI (7 bytes = 14 digits)
        for (int i = 4; i <= 10; i++) {
            // Extract high nibble (first digit)
            imei.append((data[i] >> 4) & 0x0F);
            // Extract low nibble (second digit)
            imei.append(data[i] & 0x0F);
        }
        // The 15th digit is in the high nibble of byte 11
        imei.append((data[11] >> 4) & 0x0F);

        return imei.toString();
    }
}
