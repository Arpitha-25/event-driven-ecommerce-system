package com.arpitha.inventory_service.event.incoming;

import java.util.UUID;

/**
 * This service's view of order-service's OrderCancelledEvent.
 */
public record OrderCancelledMessage(
        String eventId,
        String correlationId,
        Long orderId,
        UUID productId) {
}
