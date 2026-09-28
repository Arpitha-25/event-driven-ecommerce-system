package com.arpitha.order_service.event.consumer;

import com.arpitha.order_service.event.incoming.InventoryReservationFailedMessage;
import com.arpitha.order_service.event.incoming.InventoryReservedMessage;
import com.arpitha.order_service.idempotency.ProcessedEventStore;
import com.arpitha.order_service.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles inventory replies as idempotent consumers: the processed-event record and the
 * order update share one transaction, so a redelivered event is skipped, and a failed
 * attempt leaves no record behind and is retried in full.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventHandler {

    static final String RESERVED_CONSUMER = "order-service:inventory.reserved.v1";
    static final String FAILED_CONSUMER = "order-service:inventory.reservation-failed.v1";

    private final ProcessedEventStore processedEventStore;
    private final OrderService orderService;

    @Transactional
    public void handleInventoryReserved(InventoryReservedMessage message) {
        if (!processedEventStore.markProcessed(RESERVED_CONSUMER, message.eventId())) {
            log.info("Skipping duplicate InventoryReservedEvent {} for order {}", message.eventId(), message.orderId());
            return;
        }
        orderService.confirmOrder(message.orderId());
    }

    @Transactional
    public void handleInventoryReservationFailed(InventoryReservationFailedMessage message) {
        if (!processedEventStore.markProcessed(FAILED_CONSUMER, message.eventId())) {
            log.info("Skipping duplicate InventoryReservationFailedEvent {} for order {}",
                    message.eventId(), message.orderId());
            return;
        }
        orderService.rejectOrder(message.orderId(), message.reason());
    }
}
