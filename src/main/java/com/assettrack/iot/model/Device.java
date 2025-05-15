package com.assettrack.iot.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.assettrack.iot.storage.QueryIgnore;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/*
 * Copyright 2012 - 2023 Anton Tananaev (anton@traccar.org)
 * Copyright [Your Company] [Year]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@Entity
@Table(name = "devices")
@Getter @Setter
public class Device extends GroupedModel {

    public enum Status {
        ONLINE,
        OFFLINE,
        MOVING,
        IDLING,
        LOW_BATTERY,
        CRITICAL_BATTERY,
        DISABLED,
        UNKNOWN
    }

    // Standard fields
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(updatable = false, name = "created_at")
    private Date createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Date updatedAt;

    // Identification
    @Column(unique = true, nullable = false, length = 15)
    private String imei;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 50)
    private String model;

    @Column(name = "protocol_type", nullable = false, length = 20)
    private String protocolType; // TK102, TK103, GT06, TELTONIKA

    @Column(name = "unique_id")
    private String uniqueId;

    // Communication
    @Column(name = "phone_number", length = 20)
    private String phone;

    // Status tracking
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.UNKNOWN;

    @Column(name = "last_updated")
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastUpdated;


    @Column(name = "registration_date")
    @Temporal(TemporalType.TIMESTAMP)
    private Date registrationDate = new Date();

    // Device information
    @Column(length = 500)
    private String description;

    @Column(length = 100)
    private String contact;

    @Column(length = 50)
    private String category;

    // Power management
    @Column(name = "battery_level")
    private Double batteryLevel;

    // Activation/Expiration
    @Column(name = "is_active")
    private Boolean disabled = false;

    @Column(name = "expiration_time")
    private Date expirationTime;

    // Vehicle/driver information
    @Column(name = "current_driver", length = 100)
    private String currentDriver;

    @Column(name = "vehicle_plate", length = 20)
    private String vehiclePlate;

    // UI/Display
    @Column(name = "icon_url", length = 255)
    private String iconUrl;

    // Position tracking
    private LocalDateTime lastPositionTime;
    private Double lastLatitude;
    private Double lastLongitude;
    private Double lastSpeed;

    @Column(name = "position_id")
    private Long positionId;

    // Motion tracking
    @Column(name = "motion_state")
    private Boolean motionState;

    @Column(name = "motion_time")
    private Date motionTime;

    @Column(name = "motion_distance")
    private Double motionDistance;

    @Column(name = "motion_streak")
    private Boolean motionStreak;

    // Overspeed tracking
    @Column(name = "overspeed_state")
    private Boolean overspeedState;

    @Column(name = "overspeed_time")
    private Date overspeedTime;

    @Column(name = "overspeed_geofence_id")
    private Long overspeedGeofenceId;

    // Scheduling
    @Column(name = "calendar_id")
    private Long calendarId;

    // Relationships
    @OneToMany(mappedBy = "device", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Position> positions = new ArrayList<>();

    // Status constants for compatibility
    public static final String STATUS_UNKNOWN = "unknown";
    public static final String STATUS_ONLINE = "online";
    public static final String STATUS_OFFLINE = "offline";

    // Lifecycle methods
    @PreUpdate
    protected void onUpdate() {
        this.lastUpdated = new Date();
    }

    @PrePersist
    public void prePersist() {
        if (this.name == null || this.name.isBlank()) {
            this.name = "Device-" + (this.imei != null ? this.imei : "unknown");
        }
        if (this.uniqueId == null) {
            this.uniqueId = this.imei;
        }
    }

    // Helper methods
    public void addPosition(Position position) {
        positions.add(position);
        position.setDevice(this);
    }

    public void removePosition(Position position) {
        positions.remove(position);
        position.setDevice(null);
    }

    public Position getLatestPosition() {
        if (positions == null || positions.isEmpty()) {
            return null;
        }
        return positions.stream()
                .max((p1, p2) -> p1.getTimestamp().compareTo(p2.getTimestamp()))
                .orElse(null);
    }

    // Additional methods from Traccar model
    public void checkDisabled() {
        if (getDisabled() && getExpirationTime() != null && getExpirationTime().before(new Date())) {
            setDisabled(false);
            setExpirationTime(null);
        }
    }

    @JsonIgnore
    public boolean getMotionStreak() {
        return motionStreak != null && motionStreak;
    }

    @JsonIgnore
    public boolean getMotionState() {
        return motionState != null && motionState;
    }

    @JsonIgnore
    public boolean getOverspeedState() {
        return overspeedState != null && overspeedState;
    }

    // Status compatibility methods
    @QueryIgnore
    @JsonIgnore
    public String getStatus() {
        return status != null ? status.name().toLowerCase() : STATUS_OFFLINE;
    }

    public void setStatus(String status) {
        if (status != null) {
            try {
                this.status = Status.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Handle unknown status values
                switch (status) {
                    case STATUS_ONLINE -> this.status = Status.ONLINE;
                    case STATUS_OFFLINE -> this.status = Status.OFFLINE;
                    default -> this.status = Status.UNKNOWN;
                }
            }
        }
    }

    // Unique ID validation
    public void setUniqueId(String uniqueId) {
        if (uniqueId != null && uniqueId.contains("..")) {
            throw new IllegalArgumentException("Invalid unique id");
        }
        this.uniqueId = uniqueId != null ? uniqueId.trim() : null;
    }
}