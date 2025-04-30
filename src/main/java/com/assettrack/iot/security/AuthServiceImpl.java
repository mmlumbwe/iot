package com.assettrack.iot.security;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.repository.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AuthServiceImpl implements AuthService {
    private static final Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final DeviceRepository deviceRepository;
    private final List<String> allowedProtocols;
    private final boolean strictImeiValidation;

    @Autowired
    public AuthServiceImpl(DeviceRepository deviceRepository,
                           @Value("${security.allowed.protocols}") List<String> allowedProtocols,
                           @Value("${security.imei.strict}") boolean strictImeiValidation) {
        this.deviceRepository = deviceRepository;
        this.allowedProtocols = allowedProtocols;
        this.strictImeiValidation = strictImeiValidation;
    }

    @Override
    public boolean authenticate(String imei, String protocol, byte[] authData) {
        logger.info("Authenticating IMEI: {}, Protocol: {}", imei, protocol);

        // 1. Protocol whitelisting
        if (!allowedProtocols.contains(protocol.toUpperCase())) {
            logger.warn("Protocol {} not allowed", protocol);
            return false;
        }

        // 2. IMEI validation
        if (strictImeiValidation && !isValidImei(imei)) {
            logger.warn("Invalid IMEI format: {}", imei);
            return false;
        }

        // 3. Device registry check
        Optional<Device> device = deviceRepository.findByImei(imei);
        if (!device.isPresent()) {
            logger.warn("Device not registered: {}", imei);
            return false;
        }

        // 4. Protocol-specific authentication
        switch (protocol.toUpperCase()) {
            case "GT06":
                return authenticateGt06(imei, authData);
            case "TELTONIKA":
                return authenticateTeltonika(imei, authData);
            case "TK103":
                return authenticateTk103(imei, authData);
            default:
                logger.error("Unsupported protocol: {}", protocol);
                return false;
        }
    }

    private boolean authenticateGt06(String imei, byte[] authData) {
        if (authData == null || authData.length < 28) return false;
        if (authData[0] != 0x78 || authData[1] != 0x78) return false;
        if (authData[3] != 0x01) return false; // Login packet type

        // Additional CRC validation can be added here
        return true;
    }

    private boolean authenticateTeltonika(String imei, byte[] authData) {
        // Teltonika-specific validation
        return authData != null && authData.length > 10;
    }

    private boolean authenticateTk103(String imei, byte[] authData) {
        // TK103 typically only needs valid IMEI
        return true;
    }

    private boolean isValidImei(String imei) {
        if (imei == null || !imei.matches("\\d{15}")) return false;
        return validateLuhnCheck(imei);
    }

    private boolean validateLuhnCheck(String imei) {
        int sum = 0;
        for (int i = 0; i < imei.length(); i++) {
            int digit = Character.getNumericValue(imei.charAt(i));
            if (i % 2 != 0) {
                digit *= 2;
                if (digit > 9) digit = digit % 10 + 1;
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }
}