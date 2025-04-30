package com.assettrack.iot.service;

import com.assettrack.iot.model.Device;
import com.assettrack.iot.repository.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class DeviceService {
    private static final Logger logger = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository deviceRepository;

    public DeviceService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Transactional(readOnly = true)
    public Device findByImei(String imei) {
        try {
            if (imei == null || imei.trim().isEmpty()) {
                logger.warn("Empty IMEI provided");
                return null;
            }

            // Clean IMEI (remove any non-digit characters)
            String cleanImei = imei.replaceAll("[^0-9]", "");

            // Validate IMEI length (standard IMEI is 15 digits)
            if (cleanImei.length() != 15) {
                logger.warn("Invalid IMEI length: {}", cleanImei);
                return null;
            }

            Optional<Device> device = deviceRepository.findByImei(cleanImei);
            if (device.isEmpty()) {
                logger.warn("Device not found for IMEI: {}", cleanImei);
                return null;
            }

            // Check if device is active
            if (!device.get().getIsActive()) {
                logger.warn("Device is inactive: {}", cleanImei);
                return null;
            }

            return device.get();
        } catch (Exception e) {
            logger.error("Error finding device by IMEI: {}", imei, e);
            return null;
        }
    }

    @Transactional
    public Device createDeviceIfNotExists(String imei) {
        try {
            Device existing = findByImei(imei);
            if (existing != null) {
                return existing;
            }

            // Create new device
            Device newDevice = new Device();
            newDevice.setImei(imei);
            newDevice.setName("GT06-" + imei.substring(imei.length() - 6));
            newDevice.setIsActive(true);
            newDevice.setProtocolType("GT06");

            return deviceRepository.save(newDevice);
        } catch (Exception e) {
            logger.error("Error creating device for IMEI: {}", imei, e);
            return null;
        }
    }
}