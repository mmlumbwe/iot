package com.assettrack.iot.config;

import com.assettrack.iot.protocol.Gt06Handler;
import org.apache.coyote.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.LocalDateTime;

@Component
public class Gt06TimestampParser {
    private static final Logger logger = LoggerFactory.getLogger(Gt06Handler.class);
    private final TimestampValidationConfig config;

    @Autowired
    public Gt06TimestampParser(TimestampValidationConfig config) {
        this.config = config;
    }

    public LocalDateTime parseTimestamp(ByteBuffer buffer, String imei) throws ProtocolException {
        int year = 2000 + (buffer.get() & 0xFF);
        int month = validateField(buffer.get() & 0xFF, 1, 12, "month", imei);
        int day = validateField(buffer.get() & 0xFF, 1, 31, "day", imei);
        int hour = validateField(buffer.get() & 0xFF, 0, 23, "hour", imei);
        int minute = validateField(buffer.get() & 0xFF, 0, 59, "minute", imei);
        int second = validateField(buffer.get() & 0xFF, 0, 59, "second", imei);

        try {
            return LocalDateTime.of(year, month, day, hour, minute, second);
        } catch (DateTimeException e) {
            throw new ProtocolException("Invalid timestamp: " + e.getMessage());
        }
    }

    private int validateField(int value, int min, int max, String field, String imei)
            throws ProtocolException {
        TimestampValidationConfig.ValidationMode mode = config.getModeForField(field);

        switch (mode) {
            case STRICT:
                if (value < min || value > max) {
                    throw new ProtocolException(
                            String.format("Invalid %s value: %d (valid range %d-%d)",
                                    field, value, min, max));
                }
                return value;

            case LENIENT:
                return clampValue(value, min, max, field, imei);

            case RECOVER:
                return recoverValue(value, min, max, field, imei);

            default:
                return value;
        }
    }

    private int clampValue(int value, int min, int max, String field, String imei) {
        int clamped = Math.min(Math.max(value, min), max);
        if (clamped != value) {
            logger.warn("Clamping invalid {} from {} to {} for IMEI {}",
                    field, value, clamped, imei);
        }
        return clamped;
    }

    private int recoverValue(int value, int min, int max, String field, String imei)
            throws ProtocolException {
        // Special handling for minutes
        if ("minute".equals(field)) {
            if (value == 60) {
                logger.warn("Recovering 60 minutes as 59 for IMEI {}", imei);
                return 59;
            }
            if (value > 60 && value < 100) {
                int recovered = value % 60;
                logger.warn("Recovering invalid minute {} as {} for IMEI {}",
                        value, recovered, imei);
                return recovered;
            }
        }

        // Default recovery logic
        int recovered = value % (max + 1);
        if (recovered != value) {
            logger.warn("Recovering invalid {} {} as {} for IMEI {}",
                    field, value, recovered, imei);
        }
        return recovered;
    }
}
