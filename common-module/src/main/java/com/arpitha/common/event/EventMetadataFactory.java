package com.arpitha.common.event;

import com.arpitha.common.filter.CorrelationIdContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class EventMetadataFactory {

    @Value("${spring.application.name:unknown-service}")
    private String applicationName;

    public <T extends BaseEvent> T populateMetadata(T event, EventType eventType) {
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType.name());
        event.setEventVersion("v1");
        event.setCorrelationId(CorrelationIdContext.getCorrelationId());
        event.setSourceService(applicationName);
        event.setOccurredAt(LocalDateTime.now());
        return event;
    }
}
