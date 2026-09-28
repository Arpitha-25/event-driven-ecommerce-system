package com.arpitha.inventory_service.event.consumer;

import com.arpitha.inventory_service.event.incoming.OrderCancelledMessage;
import com.arpitha.inventory_service.event.incoming.OrderCreatedMessage;
import com.arpitha.inventory_service.idempotency.ProcessedEventStore;
import com.arpitha.inventory_service.service.ReservationResult;
import com.arpitha.inventory_service.service.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles order events as idempotent consumers. The processed-event record and the stock
 * change share one transaction, which commits when these methods return.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventHandler {

    static final String CREATED_CONSUMER = "inventory-service:order.created.v1";
    static final String CANCELLED_CONSUMER = "inventory-service:order.cancelled.v1";

    private final ProcessedEventStore processedEventStore;
    private final StockReservationService stockReservationService;

    /**
     * A duplicate event never touches stock, but its stored outcome is still returned so the
     * listener re-sends the reply. That matters when the first attempt committed and then failed
     * to send its reply: the retry arrives with the same eventId, and order-service is still waiting.
     */
    @Transactional
    public ReservationResult handleOrderCreated(OrderCreatedMessage message) {
        if (!processedEventStore.markProcessed(CREATED_CONSUMER, message.eventId())) {
            log.info("Duplicate OrderCreatedEvent {} for order {}: stock untouched, re-sending the stored outcome",
                    message.eventId(), message.orderId());
            return stockReservationService.storedOutcome(message.orderId());
        }
        return stockReservationService.reserveStock(message.orderId(), message.productId(), message.quantity());
    }

    @Transactional
    public void handleOrderCancelled(OrderCancelledMessage message) {
        if (!processedEventStore.markProcessed(CANCELLED_CONSUMER, message.eventId())) {
            log.info("Skipping duplicate OrderCancelledEvent {} for order {}", message.eventId(), message.orderId());
            return;
        }
        stockReservationService.releaseStock(message.orderId(), message.productId());
    }
}
