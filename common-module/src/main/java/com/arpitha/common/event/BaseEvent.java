package com.arpitha.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseEvent {
    private String eventId;
    private String eventType;
    private String eventVersion;
    private String correlationId;
    private String sourceService;
    private LocalDateTime occurredAt = LocalDateTime.now();
}
