package com.assettrack.iot.model.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceSessionManager {
    private final Map<String, DeviceSession> activeSessions;
    private final Logger logger;

    public DeviceSessionManager() {
        this.activeSessions = new ConcurrentHashMap<>();
        this.logger = LoggerFactory.getLogger(DeviceSessionManager.class);
    }

    public DeviceSession manageDeviceSession(String imei, short serialNumber) {
        DeviceSession session = activeSessions.compute(imei, (key, existing) -> {
            if (existing != null && !existing.isExpired()) {
                if (existing.isDuplicateSerialNumber(serialNumber)) {
                    logger.warn("Duplicate login from IMEI: {}", imei);
                } else {
                    existing.updateSerialNumber(serialNumber);
                }
                return existing;
            }
            return new DeviceSession(imei, serialNumber);
        });

        if (session.getConnectionCount() == 1) {
            logger.info("Created new session for IMEI {}", imei);
        }
        return session;
    }

    // Additional useful methods
    public DeviceSession getSession(String imei) {
        return activeSessions.get(imei);
    }

    public void removeSession(String imei) {
        activeSessions.remove(imei);
        logger.info("Removed session for IMEI: {}", imei);
    }
}