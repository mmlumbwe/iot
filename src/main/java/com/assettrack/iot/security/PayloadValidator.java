package com.assettrack.iot.security;

import com.assettrack.iot.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates incoming payloads against protocol specifications
 * and security constraints.
 */
@Component
@ConfigurationProperties(prefix = "validation")
public class PayloadValidator {
    private static final Logger logger = LoggerFactory.getLogger(PayloadValidator.class);

    private boolean validateChecksum;
    private boolean validateLength;

    // Configuration properties with defaults
    private boolean checksumValidation = true;
    private boolean lengthValidation = true;
    private int maxPayloadSize;
    private Set<String> allowedProtocols = new HashSet<>(Arrays.asList("GT06", "TELTONIKA", "TK103"));
    private boolean strictMode = false;

    // GT06 protocol constants
    private static final byte[] GT06_HEADER = {0x78, 0x78};
    private static final int GT06_MIN_LENGTH = 18;

    // Teltonika protocol constants
    private static final int TELTONIKA_MIN_LENGTH = 12;

    // TK103 protocol constants
    private static final byte[] TK103_HEADER = {'#', '#'};
    private static final int TK103_MIN_LENGTH = 20;

    /**
     * Validates a payload against protocol specifications
     * @param protocol The protocol type (GT06, TELTONIKA, TK103)
     * @param data The raw payload data
     * @throws ValidationException if validation fails
     */
    public void validate(String protocol, byte[] data) throws ValidationException {
        if (data == null) {
            throw new ValidationException("Payload cannot be null");
        }

        // Protocol whitelist check
        if (!allowedProtocols.contains(protocol)) {
            throw new ValidationException("Protocol " + protocol + " is not allowed");
        }

        // General payload size check
        if (data.length > maxPayloadSize) {
            throw new ValidationException("Payload size " + data.length + " exceeds maximum of " + maxPayloadSize);
        }

        // Protocol-specific validation
        switch (protocol.toUpperCase()) {
            case "GT06":
                validateGt06(data);
                break;
            case "TELTONIKA":
                validateTeltonika(data);
                break;
            case "TK103":
                validateTk103(data);
                break;
            default:
                if (strictMode) {
                    throw new ValidationException("Unsupported protocol: " + protocol);
                }
                logger.warn("No specific validation for protocol: {}", protocol);
        }
    }

    private void validateGt06(byte[] data) throws ValidationException {
        // Length validation
        if (lengthValidation && data.length < GT06_MIN_LENGTH) {
            throw new ValidationException("GT06 payload too short. Length: " + data.length);
        }

        // Header validation
        if (data[0] != GT06_HEADER[0] || data[1] != GT06_HEADER[1]) {
            throw new ValidationException("Invalid GT06 header");
        }

        // Checksum validation
        if (checksumValidation) {
            byte calculatedChecksum = 0;
            for (int i = 2; i < data.length - 3; i++) {
                calculatedChecksum ^= data[i];
            }

            byte receivedChecksum = data[data.length - 3];
            if (calculatedChecksum != receivedChecksum) {
                throw new ValidationException(String.format(
                        "GT06 checksum mismatch. Calculated: %02X, Received: %02X",
                        calculatedChecksum, receivedChecksum));
            }
        }

        // Protocol number validation
        byte protocolNumber = data[3];
        if (protocolNumber != 0x01 && protocolNumber != 0x12 && protocolNumber != 0x13 && protocolNumber != 0x16) {
            throw new ValidationException("Invalid GT06 protocol number: " + protocolNumber);
        }
    }

    private void validateTeltonika(byte[] data) throws ValidationException {
        if (lengthValidation && data.length < TELTONIKA_MIN_LENGTH) {
            throw new ValidationException("Teltonika payload too short. Length: " + data.length);
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Preamble validation (should be 0)
            long preamble = buffer.getInt();
            if (preamble != 0) {
                throw new ValidationException("Invalid Teltonika preamble: " + preamble);
            }

            // Data length validation
            int declaredLength = buffer.getInt();
            if (lengthValidation && (declaredLength <= 0 || declaredLength > data.length - 8)) {
                throw new ValidationException("Invalid Teltonika data length: " + declaredLength);
            }

            // Codec ID validation
            byte codecId = buffer.get();
            if (codecId != 0x08 && codecId != 0x10) { // CODEC8 and CODEC16
                throw new ValidationException("Unsupported Teltonika codec: " + codecId);
            }
        } catch (Exception e) {
            throw new ValidationException("Teltonika payload parsing error: " + e.getMessage());
        }
    }

    private void validateTk103(byte[] data) throws ValidationException {
        if (lengthValidation && data.length < TK103_MIN_LENGTH) {
            throw new ValidationException("TK103 payload too short. Length: " + data.length);
        }

        // Header validation
        if (data[0] != TK103_HEADER[0] || data[1] != TK103_HEADER[1]) {
            throw new ValidationException("Invalid TK103 header");
        }

        // Footer validation
        if (data[data.length - 2] != TK103_HEADER[0] || data[data.length - 1] != TK103_HEADER[1]) {
            throw new ValidationException("Invalid TK103 footer");
        }

        // Basic format validation
        try {
            String payload = new String(data);
            String[] parts = payload.substring(2, payload.length() - 2).split(",");
            if (parts.length < 6) {
                throw new ValidationException("Invalid TK103 format. Expected at least 6 comma-separated values");
            }
        } catch (Exception e) {
            throw new ValidationException("TK103 payload parsing error: " + e.getMessage());
        }
    }

    // Proper getter and setter methods
    public boolean isValidateChecksum() {
        return validateChecksum;
    }

    public void setValidateChecksum(boolean validateChecksum) {
        this.validateChecksum = validateChecksum;
    }

    public boolean isValidateLength() {
        return validateLength;
    }

    public void setValidateLength(boolean validateLength) {
        this.validateLength = validateLength;
    }

    // Configuration setters
    public void setChecksumValidation(boolean checksumValidation) {
        this.checksumValidation = checksumValidation;
    }

    public void setLengthValidation(boolean lengthValidation) {
        this.lengthValidation = lengthValidation;
    }

    public void setMaxPayloadSize(int maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
    }

    public void setAllowedProtocols(Set<String> allowedProtocols) {
        this.allowedProtocols = allowedProtocols;
    }

    public void setStrictMode(boolean strictMode) {
        this.strictMode = strictMode;
    }

    // Getters for configuration inspection
    public boolean isChecksumValidation() {
        return checksumValidation;
    }

    public boolean isLengthValidation() {
        return lengthValidation;
    }

    public int getMaxPayloadSize() {
        return maxPayloadSize;
    }

    public Set<String> getAllowedProtocols() {
        return allowedProtocols;
    }

    public boolean isStrictMode() {
        return strictMode;
    }
}