package com.assettrack.iot.session.cache;

import com.assettrack.iot.model.Position;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class CacheManager {

    private static final Logger logger = LoggerFactory.getLogger(CacheManager.class);

    // This cache can store the latest position for each device, or a more complex object
    // depending on what you need to quickly retrieve. For now, let's store the Position.
    private final Map<Long, Position> deviceLatestPositionCache = new ConcurrentHashMap<>();
    private final Map<Long, Object> devicePresenceCache = new ConcurrentHashMap<>(); // Original deviceCache

    public void addDevice(long deviceId) {
        // This method can be used to mark a device as "present" or "active" in the cache
        devicePresenceCache.put(deviceId, new Object());
        logger.debug("Added device {} to presence cache.", deviceId);
    }

    public void removeDevice(long deviceId) {
        devicePresenceCache.remove(deviceId);
        deviceLatestPositionCache.remove(deviceId); // Also remove from position cache
        logger.debug("Removed device {} from all caches.", deviceId);
    }

    public boolean containsDevice(long deviceId) {
        return devicePresenceCache.containsKey(deviceId);
    }

    // Corrected method: Stores or updates the latest position for a given deviceId
    public void updateDevicePosition(Long deviceId, Position position) {
        if (deviceId == null || position == null) {
            logger.warn("Attempted to update device position with null deviceId or position.");
            return;
        }
        deviceLatestPositionCache.put(deviceId, position);
        logger.info("Updated latest position for device {}: Latitude={}, Longitude={}",
                deviceId, position.getLatitude(), position.getLongitude());
    }

    // You might want a getter for the latest position
    public Position getLatestDevicePosition(Long deviceId) {
        return deviceLatestPositionCache.get(deviceId);
    }
}