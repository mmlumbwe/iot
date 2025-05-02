package com.assettrack.iot.service;

import com.assettrack.iot.model.Position;
import com.assettrack.iot.model.Device;
import com.assettrack.iot.protocol.ProtocolHandler;
import com.assettrack.iot.repository.PositionRepository;
import com.assettrack.iot.repository.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PositionService {
    private static final Logger logger = LoggerFactory.getLogger(PositionService.class);

    private final List<ProtocolHandler> protocolHandlers;
    private final PositionRepository positionRepository;
    private final DeviceRepository deviceRepository;

    public PositionService(List<ProtocolHandler> protocolHandlers,
                           PositionRepository positionRepository,
                           DeviceRepository deviceRepository) {
        this.protocolHandlers = protocolHandlers;
        this.positionRepository = positionRepository;
        this.deviceRepository = deviceRepository;
    }


    @Transactional
    public Position savePosition(Position position) {
        try {
            logger.debug("Attempting to save position for device {}", position.getDevice().getImei());

            // 1. Validate position
            if (!isValidPosition(position)) {
                throw new IllegalArgumentException("Invalid position data");
            }

            // 2. Ensure device exists
            Device device = deviceRepository.findByImei(position.getDevice().getImei())
                    .orElseGet(() -> {
                        logger.info("Creating new device with IMEI: {}", position.getDevice().getImei());
                        Device newDevice = new Device();
                        newDevice.setImei(position.getDevice().getImei());
                        newDevice.setProtocolType("GT06"); // Default protocol
                        return deviceRepository.save(newDevice);
                    });

            // 3. Set the device reference
            position.setDevice(device);

            // 4. Save and flush to ensure immediate persistence
            Position savedPosition = positionRepository.saveAndFlush(position);

            logger.info("Saved position ID {} for device {}",
                    savedPosition.getId(), device.getImei());

            return savedPosition;
        } catch (Exception e) {
            logger.error("Failed to save position for device {}: {}",
                    position.getDevice().getImei(), e.getMessage(), e);
            throw e;
        }
    }


    @Transactional
    public void processPosition(Position position) {
        try {
            logger.debug("Starting position processing for device {}",
                    position.getDevice().getImei());

            // 1. Validate position
            if (!isValidPosition(position)) {
                logger.warn("Invalid position received: {}", position);
                return;
            }

            // 2. Get or create device
            Device device = deviceRepository.findByImei(position.getDevice().getImei())
                    .orElseGet(() -> {
                        logger.info("Creating new device with IMEI: {}", position.getDevice().getImei());
                        Device newDevice = new Device();
                        newDevice.setImei(position.getDevice().getImei());
                        newDevice.setProtocolType(position.getDevice().getProtocolType() != null ?
                                position.getDevice().getProtocolType() : "GT06");
                        return deviceRepository.save(newDevice);
                    });

            position.setDevice(device);

            // 3. Update device last known position
            updateDevicePosition(position);

            // 4. Save position history
            savePositionHistory(position);

            logger.info("Successfully processed position for device {}", device.getImei());

        } catch (Exception e) {
            logger.error("Error processing position for device {}: {}",
                    position.getDevice().getImei(), e.getMessage(), e);
            throw new RuntimeException("Failed to process position", e);
        }
    }


    @Transactional
    public Position processAndSavePosition(Position position) {
        try {
            // 1. Validate position data
            if (!isValidPosition(position)) {
                throw new IllegalArgumentException("Invalid position data");
            }

            // 2. Get or create device
            Device device = deviceRepository.findByImei(position.getDevice().getImei())
                    .orElseGet(() -> createNewDevice(position.getDevice().getImei()));

            // 3. Update device with latest position
            updateDevicePosition(device, position);

            // 4. Create and save position record
            Position newPosition = createPositionRecord(device, position);
            Position savedPosition = positionRepository.saveAndFlush(newPosition);

            logger.info("Persisted position ID {} for device {}",
                    savedPosition.getId(), device.getImei());

            return savedPosition;
        } catch (Exception e) {
            logger.error("Position persistence failed for device {}: {}",
                    position.getDevice().getImei(), e.getMessage(), e);
            throw e;
        }
    }

    private Device createNewDevice(String imei) {
        logger.info("Creating new device with IMEI: {}", imei);
        Device device = new Device();
        device.setImei(imei);
        device.setProtocolType("GT06");
        //device.setCreatedAt(LocalDateTime.now());
        return deviceRepository.saveAndFlush(device);
    }

    private void updateDevicePosition(Device device, Position position) {
        device.setLastPositionTime(position.getTimestamp());
        device.setLastLatitude(position.getLatitude());
        device.setLastLongitude(position.getLongitude());
        device.setLastSpeed(position.getSpeed());
        device.setLastUpdate(LocalDateTime.now());
        deviceRepository.saveAndFlush(device);
        logger.debug("Updated device {} position", device.getImei());
    }

    private Position createPositionRecord(Device device, Position position) {
        Position newPosition = new Position();
        newPosition.setDevice(device);
        newPosition.setTimestamp(position.getTimestamp());
        newPosition.setLatitude(position.getLatitude());
        newPosition.setLongitude(position.getLongitude());
        newPosition.setSpeed(position.getSpeed());
        newPosition.setCourse(position.getCourse());
        newPosition.setValid(position.isValid());
        // Copy other relevant fields
        return newPosition;
    }

    private boolean isValidPosition(Position position) {
        if (position == null || position.getDevice() == null) {
            logger.warn("Position or device is null");
            return false;
        }

        if (position.getDevice().getImei() == null) {
            logger.warn("Device IMEI is null");
            return false;
        }

        if (position.getTimestamp() == null) {
            logger.warn("Position timestamp is null");
            return false;
        }

        if (position.getLatitude() == null || position.getLongitude() == null) {
            logger.warn("Invalid coordinates");
            return false;
        }

        return true;
    }

    @Transactional
    protected void updateDevicePosition(Position position) {
        try {
            Device device = position.getDevice();
            logger.debug("Updating last position for device {}", device.getImei());

            device.setLastPositionTime(position.getTimestamp());
            device.setLastLatitude(position.getLatitude());
            device.setLastLongitude(position.getLongitude());
            device.setLastSpeed(position.getSpeed());
            device.setLastUpdate(LocalDateTime.now());

            deviceRepository.saveAndFlush(device);
            logger.debug("Successfully updated device {} position", device.getImei());
        } catch (Exception e) {
            logger.error("Failed to update device position: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    protected void savePositionHistory(Position position) {
        try {
            logger.debug("Saving position history for device {}", position.getDevice().getImei());

            Position newPosition = new Position();
        // Copy all relevant fields
        newPosition.setDevice(position.getDevice());
        newPosition.setTimestamp(position.getTimestamp());
        newPosition.setLatitude(position.getLatitude());
        newPosition.setLongitude(position.getLongitude());
        newPosition.setSpeed(position.getSpeed());
        newPosition.setCourse(position.getCourse());
        newPosition.setValid(position.isValid());
        newPosition.setSatellites(position.getSatellites());
        newPosition.setBatteryLevel(position.getBatteryLevel());
        newPosition.setIgnition(position.getIgnition());
        newPosition.setAlarmType(position.getAlarmType());
        newPosition.setAttributes(position.getAttributes());

            positionRepository.saveAndFlush(newPosition);
            logger.info("Successfully saved position history for device {}",
                    position.getDevice().getImei());
        } catch (Exception e) {
            logger.error("Failed to save position history: {}", e.getMessage(), e);
            throw e;
        }
    }


    public List<Position> getPositionsByDeviceImei(String imei) {
        return positionRepository.findByDeviceImeiOrderByTimestampDesc(imei);
    }

    public List<Position> getLatestPositions() {
        return positionRepository.findTop100ByOrderByTimestampDesc();
    }

    public Optional<Position> getPositionById(Long id) {
        return positionRepository.findById(id);
    }

    private ProtocolHandler findHandler(String protocolType) {
        return protocolHandlers.stream()
                .filter(handler -> handler.supports(protocolType))
                .findFirst()
                .orElse(null);
    }
}