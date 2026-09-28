package com.arpitha.order_service.event.incoming;

import java.util.UUID;

/**
 * This service's view of inventory-service's InventoryReservationFailedEvent.
 */
public record InventoryReservationFailedMessage(
        String eventId,
        String correlationId,
        Long orderId,
        UUID productId,
        Integer quantity,
        String reason) {
}
