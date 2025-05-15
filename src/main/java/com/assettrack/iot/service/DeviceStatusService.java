package com.assettrack.iot.service;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.model.Position;
import com.assettrack.iot.repository.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DeviceStatusService {
    private static final Logger logger = LoggerFactory.getLogger(DeviceStatusService.class);

    private static final int INACTIVITY_THRESHOLD_MINUTES = 15;
    private static final double LOW_BATTERY_THRESHOLD = 3.5;
    private static final double CRITICAL_BATTERY_THRESHOLD = 3.0;

    private final DeviceRepository deviceRepository;
    //private final NotificationService notificationService;
    private final Map<String, Device.Status> deviceStatusCache = new HashMap<>();

    public DeviceStatusService(DeviceRepository deviceRepository
                               //,NotificationService notificationService
    ) {
        this.deviceRepository = deviceRepository;
        //this.notificationService = notificationService;
    }

    @Transactional
    public void processDevice(Device device) {
        if (device == null || device.getImei() == null) {
            logger.warn("Attempted to process null device or device with null IMEI");
            return;
        }

        // Check if device exists in database
        Optional<Device> existingDevice = deviceRepository.findByImei(device.getImei());

        if (existingDevice.isPresent()) {
            Device dbDevice = existingDevice.get();

            // Update mutable fields
            dbDevice.setName(device.getName());
            dbDevice.setModel(device.getModel());
            //dbDevice.setPhoneNumber(device.getPhoneNumber());
            dbDevice.setDescription(device.getDescription());
            dbDevice.setVehiclePlate(device.getVehiclePlate());
            dbDevice.setCurrentDriver(device.getCurrentDriver());
            dbDevice.setIconUrl(device.getIconUrl());

            // Maintain original registration date and status
            device = dbDevice;
        } else {
            // Set initial status for new devices
            //device.setStatus(Device.Status.ONLINE);
            device.setRegistrationDate(new Date());
            device = deviceRepository.save(device);
            logger.info("Registered new device: IMEI {}", device.getImei());
        }

        // Update last updated timestamp
        device.setLastUpdated(new Date());
        deviceRepository.save(device);

        // Update status cache
        //deviceStatusCache.put(device.getImei(), device.getStatus());
    }

    /*@Transactional
    public void updateDeviceStatus(Position position) {
        Device device = position.getDevice();
        if (device == null || device.getImei() == null) return;

        Device.Status status = calculateDeviceStatus(position);
        device.setStatus(status);
        device.setLastUpdated(new Date());

        // Only update if status changed
        if (!status.equals(deviceStatusCache.get(device.getImei()))) {
            deviceRepository.save(device);
            deviceStatusCache.put(device.getImei(), status);
            triggerNotifications(device, position);
        }
    }

    @Scheduled(fixedRate = 300000) // Every 5 minutes
    @Transactional
    public void checkInactiveDevices() {
        Date threshold = Date.from(LocalDateTime.now()
                .minusMinutes(INACTIVITY_THRESHOLD_MINUTES)
                .atZone(ZoneId.systemDefault())
                .toInstant());

        List<Device> inactiveDevices = deviceRepository
                .findByLastUpdatedBefore(threshold);

        inactiveDevices.forEach(device -> {
            device.setStatus(Device.Status.OFFLINE);
            deviceRepository.save(device);
            deviceStatusCache.put(device.getImei(), Device.Status.OFFLINE);
            notificationService.sendInactivityAlert(device);
        });
    }

    public Device.Status getDeviceStatus(String imei) {
        return deviceStatusCache.getOrDefault(imei, Device.Status.UNKNOWN);
    }

    public Map<String, Device.Status> getAllDeviceStatuses() {
        return new HashMap<>(deviceStatusCache);
    }

    public List<Device> getDevicesByStatus(Device.Status status) {
        return deviceStatusCache.entrySet().stream()
                .filter(entry -> entry.getValue().equals(status))
                .map(entry -> deviceRepository.findByImei(entry.getKey()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    private Device.Status calculateDeviceStatus(Position position) {
        if (position.getBatteryLevel() != null) {
            if (position.getBatteryLevel() < CRITICAL_BATTERY_THRESHOLD) {
                return Device.Status.CRITICAL_BATTERY;
            } else if (position.getBatteryLevel() < LOW_BATTERY_THRESHOLD) {
                return Device.Status.LOW_BATTERY;
            }
        }

        if (position.getIgnition() != null && position.getIgnition()) {
            return position.getSpeed() > 0
                    ? Device.Status.MOVING
                    : Device.Status.IDLING;
        }

        return Device.Status.ONLINE;
    }

    private void triggerNotifications(Device device, Position position) {
        switch (device.getStatus()) {
            case CRITICAL_BATTERY:
                notificationService.sendCriticalBatteryAlert(device, position);
                break;
            case LOW_BATTERY:
                notificationService.sendLowBatteryAlert(device, position);
                break;
            case OFFLINE:
                notificationService.sendDeviceOfflineAlert(device);
                break;
            case MOVING:
                if (position.getAlarmType() != null) {
                    notificationService.sendAlarmNotification(device, position);
                }
                break;
        }
    }*/
}