package com.assettrack.iot.session.cache;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CacheManager {

    private final Map<Long, Object> deviceCache = new ConcurrentHashMap<>();

    public void addDevice(long deviceId) {
        deviceCache.put(deviceId, new Object());
    }

    public void removeDevice(long deviceId) {
        deviceCache.remove(deviceId);
    }

    public boolean containsDevice(long deviceId) {
        return deviceCache.containsKey(deviceId);
    }
}