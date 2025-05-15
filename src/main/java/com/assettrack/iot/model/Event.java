package com.assettrack.iot.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Getter @Setter
public class Event {
    @Id
    private Long id;
    private Long deviceId;
    private String type;
    private LocalDateTime timestamp;
    // Add other event properties
}