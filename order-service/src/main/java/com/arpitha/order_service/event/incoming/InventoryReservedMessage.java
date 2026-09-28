package com.arpitha.order_service.event.incoming;

import java.util.UUID;

/**
 * This service's view of inventory-service's InventoryReservedEvent.
 * Only the fields the order saga needs; unknown fields are ignored.
 */
public record InventoryReservedMessage(
        String eventId,
        String correlationId,
        Long orderId,
        UUID productId,
        Integer quantity) {
}
