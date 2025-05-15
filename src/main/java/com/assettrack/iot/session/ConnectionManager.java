package com.assettrack.iot.session;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.assettrack.iot.session.cache.CacheManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConnectionManager {

    private final Map<Long, DeviceSession> sessionsByDeviceId = new ConcurrentHashMap<>();

    @Autowired
    private CacheManager cacheManager;

    public DeviceSession getDeviceSession(long deviceId) {
        return sessionsByDeviceId.get(deviceId);
    }

    public void addSession(DeviceSession session) {
        sessionsByDeviceId.put(session.getDeviceId(), session);
        cacheManager.addDevice(session.getDeviceId());
    }

    public void removeSession(long deviceId) {
        sessionsByDeviceId.remove(deviceId);
        cacheManager.removeDevice(deviceId);
    }

    @Scheduled(fixedRate = 30000)
    public void checkTimeouts() {
        long currentTime = System.currentTimeMillis();
        sessionsByDeviceId.entrySet().removeIf(entry ->
                currentTime - entry.getValue().getLastUpdate() > 180000
        );
    }
}