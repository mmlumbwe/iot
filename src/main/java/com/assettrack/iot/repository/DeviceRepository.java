package com.assettrack.iot.repository;

import com.assettrack.iot.model.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    // Find device by IMEI
    Optional<Device> findByImei(String imei);

    // Find devices by protocol type
    List<Device> findByProtocolType(String protocolType);
    boolean existsByImei(String imei);

    // Find active devices (with positions in last X hours)
    /*@Query("SELECT d FROM Device d WHERE d.id IN " +
            "(SELECT p.device.id FROM Position p WHERE p.timestamp >= CURRENT_TIMESTAMP - FUNCTION('HOUR', :hours))")
    List<Device> findActiveDevices(int hours);*/

    // Find devices with low battery
    @Query("SELECT DISTINCT p.device FROM Position p " +
            "WHERE p.batteryLevel IS NOT NULL AND p.batteryLevel < :threshold " +
            "ORDER BY p.timestamp DESC")
    List<Device> findDevicesWithLowBattery(double threshold);

    // Count devices by protocol type
    @Query("SELECT d.protocolType, COUNT(d) FROM Device d GROUP BY d.protocolType")
    List<Object[]> countDevicesByProtocolType();

    // Find devices by model containing text (case insensitive)
    List<Device> findByModelContainingIgnoreCase(String model);

    // Find devices with phone number
    List<Device> findByPhoneNumberIsNotNull();

    // Custom query to find devices with specific description pattern
    @Query("SELECT d FROM Device d WHERE d.description LIKE %:keyword%")
    List<Device> searchByDescription(String keyword);

    List<Device> findByLastUpdatedBefore(Date threshold);
}