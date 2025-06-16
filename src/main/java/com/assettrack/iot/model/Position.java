package com.assettrack.iot.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Date;

@Entity
@Table(name = "positions")
@Getter @Setter
public class Position {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;



    @Column(name = "protocol", length = 20)
    private String protocol; // TK102, TK103, GT06, TELTONIKA

    private LocalDateTime timestamp;
    private Double latitude;
    private Double longitude;
    private Double speed; // in knots
    private Double course;
    private Boolean valid;

    // Common attributes
    private Double batteryLevel;
    private Boolean ignition;
    private Integer satellites;
    private String alarmType;

    // Extended attributes (stored as JSON)
    @Column(columnDefinition = "TEXT")
    private String attributes;

    public boolean isValid() {
        return true;
    }
}
