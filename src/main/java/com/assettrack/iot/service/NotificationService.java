package com.assettrack.iot.service;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.Position;

public interface NotificationService {
    void sendCriticalBatteryAlert(Device device, Position position);
    void sendLowBatteryAlert(Device device, Position position);
    void sendDeviceOfflineAlert(Device device);
    void sendInactivityAlert(Device device);
    void sendAlarmNotification(Device device, Position position);
}
