package com.assettrack.iot.controller;

import com.assettrack.iot.model.Position;
import com.assettrack.iot.repository.PositionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/positions")
public class PositionController {
    private final PositionRepository positionRepository;

    @Autowired
    public PositionController(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    @GetMapping
    public List<Position> getAllPositions(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        if (deviceId != null && protocol != null && date != null) {
            Date startDate = java.sql.Date.valueOf(date);
            Date endDate = java.sql.Date.valueOf(date.plusDays(1));
            List<Position> positions = positionRepository
                    .findByDeviceImeiAndProtocolAndTimestampBetween(deviceId, protocol, startDate, endDate);
            if (positions.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No positions found");
            }
            return positions;
        } else if (deviceId != null) {
            List<Position> positions = positionRepository.findByDeviceImei(deviceId);
            if (positions.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found");
            }
            return positions;
        } else if (protocol != null) {
            List<Position> positions = positionRepository.findByDeviceProtocolType(protocol);
            if (positions.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Protocol not found");
            }
            return positions;
        }
        return positionRepository.findAll();
    }

    @GetMapping("/{id}")
    public Position getPositionById(@PathVariable Long id) {
        return positionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Position not found with id: " + id));
    }

    @GetMapping("/latest")
    public List<Position> getLatestPositions() {
        return positionRepository.findTop100ByOrderByTimestampDesc();
    }
}