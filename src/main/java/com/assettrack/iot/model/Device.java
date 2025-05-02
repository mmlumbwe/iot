package com.assettrack.iot.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@Table(name = "devices")
@Getter @Setter
public class Device {

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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(updatable = false, name = "created_at")
    private Date createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Date updatedAt;

    @Column(unique = true, nullable = false, length = 15)
    private String imei;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 50)
    private String model;

    @Column(name = "protocol_type", nullable = false, length = 20)
    private String protocolType; // TK102, TK103, GT06, TELTONIKA

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.UNKNOWN;

    @Column(name = "last_updated")
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastUpdated;

    @Column(name = "registration_date")
    @Temporal(TemporalType.TIMESTAMP)
    private Date registrationDate = new Date();

    @Column(name = "battery_level")
    private Double batteryLevel;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "current_driver", length = 100)
    private String currentDriver;

    @Column(name = "vehicle_plate", length = 20)
    private String vehiclePlate;

    @Column(name = "icon_url", length = 255)
    private String iconUrl;

    private LocalDateTime lastPositionTime;
    private Double lastLatitude;
    private Double lastLongitude;
    private Double lastSpeed;

    private LocalDateTime lastUpdate;

    @OneToMany(mappedBy = "device", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Position> positions = new ArrayList<>();

    @PreUpdate
    protected void onUpdate() {
        this.lastUpdated = new Date();
    }

    // Add default name if not set
    @PrePersist
    public void prePersist() {
        if (this.name == null || this.name.isBlank()) {
            this.name = "Device-" + (this.imei != null ? this.imei : "unknown");
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

    /*public Device(String imei, String protocolType) {
        this.imei = imei;
        this.protocolType = protocolType;  // Ensure this is set
    }*/
}