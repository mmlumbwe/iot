package com.assettrack.iot.session;

import com.assettrack.iot.service.PositionService;
import com.assettrack.iot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class ConnectionStateManager {
    private static final Logger logger = LoggerFactory.getLogger(com.assettrack.iot.session.ConnectionStateManager.class);

    private final com.assettrack.iot.session.SessionManager sessionManager;
    private final PositionService positionService;
    private final Duration staleTimeout = Duration.ofMinutes(5);

    public ConnectionStateManager(SessionManager sessionManager, PositionService positionService) {
        this.sessionManager = sessionManager;
        this.positionService = positionService;
    }

    @Scheduled(fixedRate = 60000) // 1 minute
    public void checkConnectionStates() {
        sessionManager.getAllSessions().forEach(session -> {
            if (session.isStale(staleTimeout)) {
                updateDeviceStatus(session.getImei(), "OFFLINE");
            }
        });
    }

    public void updateDeviceStatus(String imei, String status) {
        try {
            positionService.updateDeviceStatus(imei, status);
            logger.info("Updated device {} status to {}", imei, status);
        } catch (Exception e) {
            logger.error("Failed to update status for device {}", imei, e);
        }
    }
}
