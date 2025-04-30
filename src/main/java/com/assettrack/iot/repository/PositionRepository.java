package com.assettrack.iot.repository;
import com.assettrack.iot.model.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Date;
import java.util.List;

public interface PositionRepository extends JpaRepository<Position, Long> {
    List<Position> findByDeviceId(Long deviceId);
    //List<Position> findByProtocolType(String protocol);
    List<Position> findByTimestampBetween(Date start, Date end);
    //List<Position> findByDeviceIdAndProtocolAndTimestampBetween(String deviceId, String protocol, Date start, Date end);
    List<Position> findByDeviceImeiOrderByTimestampDesc(String imei);
    List<Position> findTop100ByOrderByTimestampDesc();

    @Query("SELECT p FROM Position p WHERE p.timestamp = " +
            "(SELECT MAX(p2.timestamp) FROM Position p2 WHERE p2.device.id = p.device.id)")
    List<Position> findLatestPositions();

    List<Position> findByDeviceImeiAndProtocolAndTimestampBetween(String deviceId, String protocol, Date startDate, Date endDate);

    List<Position> findByDeviceImei(String imei);

    List<Position> findByDeviceProtocolType(String protocolType);
    List<Position> findByDeviceImeiAndDeviceProtocolTypeAndTimestampBetween(
            String imei,
            String protocolType,
            Date start,
            Date end);
}
