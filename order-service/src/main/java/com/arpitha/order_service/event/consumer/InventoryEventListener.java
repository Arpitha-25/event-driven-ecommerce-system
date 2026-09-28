package com.arpitha.order_service.event.consumer;

import com.arpitha.common.filter.CorrelationIdContext;
import com.arpitha.order_service.event.incoming.InventoryReservationFailedMessage;
import com.arpitha.order_service.event.incoming.InventoryReservedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventListener {

    private final InventoryEventHandler inventoryEventHandler;

    @KafkaListener(topics = "${app.kafka.topics.inventory-reserved}")
    public void onInventoryReserved(InventoryReservedMessage message) {
        CorrelationIdContext.setCorrelationId(message.correlationId());
        try {
            log.info("Received InventoryReservedEvent {} for order {}", message.eventId(), message.orderId());
            inventoryEventHandler.handleInventoryReserved(message);
        } finally {
            CorrelationIdContext.clear();
        }
    }

    @KafkaListener(topics = "${app.kafka.topics.inventory-reservation-failed}")
    public void onInventoryReservationFailed(InventoryReservationFailedMessage message) {
        CorrelationIdContext.setCorrelationId(message.correlationId());
        try {
            log.info("Received InventoryReservationFailedEvent {} for order {}: {}",
                    message.eventId(), message.orderId(), message.reason());
            inventoryEventHandler.handleInventoryReservationFailed(message);
        } finally {
            CorrelationIdContext.clear();
        }
    }
}
