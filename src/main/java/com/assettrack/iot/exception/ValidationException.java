package com.assettrack.iot.exception;

import java.util.Arrays;

/**
 * Custom exception for payload validation failures.
 * Includes additional fields for protocol-specific error details.
 */
public class ValidationException extends Exception {
    private final String protocol;
    private final String validationType;
    private final byte[] payloadFragment;

    /**
     * Constructs a new validation exception
     * @param message The detail message
     */
    public ValidationException(String message) {
        super(message);
        this.protocol = "UNKNOWN";
        this.validationType = "GENERAL";
        this.payloadFragment = new byte[0];
    }

    /**
     * Constructs a protocol-specific validation exception
     * @param protocol The protocol being validated
     * @param validationType The type of validation that failed
     * @param message The detail message
     * @param payloadFragment Relevant portion of the payload (max 16 bytes)
     */
    public ValidationException(String protocol, String validationType,
                               String message, byte[] payloadFragment) {
        super(String.format("[%s:%s] %s", protocol, validationType, message));
        this.protocol = protocol;
        this.validationType = validationType;
        this.payloadFragment = payloadFragment != null
                ? Arrays.copyOf(payloadFragment, Math.min(payloadFragment.length, 16))
                : new byte[0];
    }

    /**
     * @return The protocol being validated when error occurred
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * @return The type of validation that failed (e.g., "CHECKSUM", "HEADER")
     */
    public String getValidationType() {
        return validationType;
    }

    /**
     * @return Relevant portion of the payload (for debugging)
     */
    public byte[] getPayloadFragment() {
        return payloadFragment.clone();
    }

    /**
     * @return Hex string representation of the payload fragment
     */
    public String getPayloadFragmentHex() {
        StringBuilder hex = new StringBuilder();
        for (byte b : payloadFragment) {
            hex.append(String.format("%02X ", b));
        }
        return hex.toString().trim();
    }
}