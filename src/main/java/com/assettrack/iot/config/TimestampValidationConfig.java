package com.assettrack.iot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gt06.timestamp")
public class TimestampValidationConfig {
    public enum ValidationMode { STRICT, LENIENT, RECOVER }

    private ValidationMode mode = ValidationMode.STRICT;
    private ValidationMode minuteMode;
    private int maxRecoveryAttempts = 3;

    // Getters and setters
    public ValidationMode getModeForField(String field) {
        if ("minute".equalsIgnoreCase(field) && minuteMode != null) {
            return minuteMode;
        }
        return mode;
    }
}
