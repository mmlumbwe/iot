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
        if (position.getDevice() == null || position.getDevice().getImei() == null) {
            throw new IllegalArgumentException("Position must have a valid device with IMEI");
        }

        // Ensure device exists
        Device device = deviceRepository.findByImei(position.getDevice().getImei())
                .orElseThrow(() -> new IllegalArgumentException("Device not found for IMEI: " + position.getDevice().getImei()));

        position.setDevice(device);
        return positionRepository.save(position);
    }

    @Transactional
    public void processPosition(Position position) {
        try {
            // 1. Validate position
            if (!isValidPosition(position)) {
                logger.warn("Invalid position received: {}", position);
                return;
            }

            // 2. Update device last known position
            updateDevicePosition(position);

            // 3. Save position history
            savePositionHistory(position);

        } catch (Exception e) {
            logger.error("Error processing position: {}", position, e);
            throw new RuntimeException("Failed to process position", e);
        }
    }

    private boolean isValidPosition(Position position) {
        return position != null &&
                position.getDevice() != null &&
                position.getDevice().getImei() != null &&
                position.getTimestamp() != null &&
                position.getLatitude() != null &&
                position.getLongitude() != null;
    }

    @Transactional
    protected void updateDevicePosition(Position position) {
        Device device = position.getDevice();

        device.setLastPositionTime(position.getTimestamp());
        device.setLastLatitude(position.getLatitude());
        device.setLastLongitude(position.getLongitude());
        device.setLastSpeed(position.getSpeed());

        device.setLastUpdate(LocalDateTime.now());
        deviceRepository.save(device);
        logger.debug("Updated device {} last position coordinates", device.getImei());
    }

    @Transactional
    protected void savePositionHistory(Position position) {
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

        positionRepository.save(newPosition);
        logger.debug("Saved position history for device {}", position.getDevice().getImei());
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