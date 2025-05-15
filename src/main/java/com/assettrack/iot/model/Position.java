package com.assettrack.iot.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.assettrack.iot.model.QueryIgnore;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Entity
@Table(name = "positions")
@Getter @Setter
public class Position extends Message {

    // Common identifiers
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    // Protocol information
    @Column(name = "protocol", length = 20)
    private String protocol; // TK102, TK103, GT06, TELTONIKA

    // Time-related fields
    private LocalDateTime timestamp;
    private Date deviceTime;
    private Date fixTime;
    private Date serverTime = new Date();

    // Location data
    private Double latitude;
    private Double longitude;
    private Double altitude;
    private Double speed; // in knots
    private Double course;
    private Double accuracy;
    private Boolean valid;
    private Boolean outdated;
    private String address;

    // Device status
    private Double batteryLevel; // percentage
    private Double power; // volts
    private Boolean ignition;
    private Integer satellites;
    private Integer rssi; // signal strength
    private Double hdop; // horizontal dilution of precision
    private Double vdop; // vertical dilution of precision
    private Double pdop; // position dilution of precision

    // Alarms and events
    private String alarmType;
    private String status;
    private String event;

    // Network information
    private String ip;
    private String operator;
    private String iccid;

    // Movement data
    private Double odometer; // meters
    private Double totalDistance; // meters
    private Double rpm;
    private Boolean motion;

    // Extended attributes (stored as JSON)
    @Column(columnDefinition = "TEXT")
    private String attributes;

    // Geofence information
    @Transient
    private List<Long> geofenceIds;

    // Network information object
    @Transient
    private Network network;

    // Constants for attribute keys (from Traccar model)
    public static final String KEY_ORIGINAL = "raw";
    public static final String KEY_INDEX = "index";
    public static final String KEY_HDOP = "hdop";
    public static final String KEY_VDOP = "vdop";
    public static final String KEY_PDOP = "pdop";
    public static final String KEY_SATELLITES = "sat";
    public static final String KEY_SATELLITES_VISIBLE = "satVisible";
    public static final String KEY_RSSI = "rssi";
    public static final String KEY_GPS = "gps";
    public static final String KEY_ROAMING = "roaming";
    public static final String KEY_EVENT = "event";
    public static final String KEY_ALARM = "alarm";
    public static final String KEY_STATUS = "status";
    public static final String KEY_ODOMETER = "odometer";
    public static final String KEY_ODOMETER_SERVICE = "serviceOdometer";
    public static final String KEY_ODOMETER_TRIP = "tripOdometer";
    public static final String KEY_HOURS = "hours";
    public static final String KEY_POWER = "power";
    public static final String KEY_BATTERY = "battery";
    public static final String KEY_BATTERY_LEVEL = "batteryLevel";
    public static final String KEY_FUEL_LEVEL = "fuel";
    public static final String KEY_FUEL_USED = "fuelUsed";
    public static final String KEY_FUEL_CONSUMPTION = "fuelConsumption";
    public static final String KEY_IGNITION = "ignition";
    public static final String KEY_FLAGS = "flags";
    public static final String KEY_CHARGE = "charge";
    public static final String KEY_IP = "ip";
    public static final String KEY_DISTANCE = "distance";
    public static final String KEY_TOTAL_DISTANCE = "totalDistance";
    public static final String KEY_RPM = "rpm";
    public static final String KEY_VIN = "vin";
    public static final String KEY_MOTION = "motion";
    public static final String KEY_OPERATOR = "operator";
    public static final String KEY_ICCID = "iccid";
    public static final String KEY_DRIVING_TIME = "drivingTime";

    // Alarm type constants
    public static final String ALARM_GENERAL = "general";
    public static final String ALARM_SOS = "sos";
    public static final String ALARM_VIBRATION = "vibration";
    public static final String ALARM_MOVEMENT = "movement";
    public static final String ALARM_LOW_SPEED = "lowspeed";
    public static final String ALARM_OVERSPEED = "overspeed";
    public static final String ALARM_FALL_DOWN = "fallDown";
    public static final String ALARM_LOW_POWER = "lowPower";
    public static final String ALARM_LOW_BATTERY = "lowBattery";
    public static final String ALARM_FAULT = "fault";
    public static final String ALARM_POWER_OFF = "powerOff";
    public static final String ALARM_POWER_ON = "powerOn";
    public static final String ALARM_DOOR = "door";
    public static final String ALARM_GEOFENCE = "geofence";
    public static final String ALARM_GEOFENCE_ENTER = "geofenceEnter";
    public static final String ALARM_GEOFENCE_EXIT = "geofenceExit";
    public static final String ALARM_ACCIDENT = "accident";
    public static final String ALARM_TOW = "tow";
    public static final String ALARM_IDLE = "idle";
    public static final String ALARM_HIGH_RPM = "highRpm";
    public static final String ALARM_TEMPERATURE = "temperature";
    public static final String ALARM_PARKING = "parking";

    // Helper methods
    @QueryIgnore
    public void setTime(Date time) {
        setDeviceTime(time);
        setFixTime(time);
    }

    public boolean isValid() {
        return valid != null && valid;
    }

    public void addAlarm(String alarm) {
        if (alarm != null) {
            if (alarmType == null) {
                alarmType = alarm;
            } else {
                alarmType += "," + alarm;
            }
        }
    }

    // Methods to handle extended attributes
    public void set(String key, Object value) {
        // Implement attribute map handling if needed
    }

    public Object get(String key) {
        // Implement attribute map handling if needed
        return null;
    }

    public boolean hasAttribute(String key) {
        // Implement attribute map handling if needed
        return false;
    }

    // Inner class for network information
    public static class Network {
        private Integer radioType;
        private String carrier;
        private Integer mcc;
        private Integer mnc;
        private Integer lac;
        private Integer cid;
        private Integer rssi;

        // Getters and setters
    }
}