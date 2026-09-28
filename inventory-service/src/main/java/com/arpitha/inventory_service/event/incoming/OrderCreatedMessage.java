package com.arpitha.inventory_service.event.incoming;

import java.util.UUID;

/**
 * This service's view of order-service's OrderCreatedEvent.
 * Only the fields a reservation needs; unknown fields are ignored.
 */
public record OrderCreatedMessage(
        String eventId,
        String correlationId,
        Long orderId,
        UUID productId,
        Integer quantity) {
}
