package com.arpitha.inventory_service.service;

import java.util.UUID;

/**
 * Outcome of handling an order for stock. The listener publishes an event based on it
 * after the database transaction has committed.
 */
public record ReservationResult(Outcome outcome, Long orderId, UUID productId, Integer quantity, String reason) {

    public enum Outcome {
        RESERVED,
        FAILED,
        /** Nothing to report back, e.g. the order was already cancelled. */
        IGNORED
    }

    public static ReservationResult reserved(Long orderId, UUID productId, Integer quantity) {
        return new ReservationResult(Outcome.RESERVED, orderId, productId, quantity, null);
    }

    public static ReservationResult failed(Long orderId, UUID productId, Integer quantity, String reason) {
        return new ReservationResult(Outcome.FAILED, orderId, productId, quantity, reason);
    }

    public static ReservationResult ignored(Long orderId) {
        return new ReservationResult(Outcome.IGNORED, orderId, null, null, null);
    }
}
