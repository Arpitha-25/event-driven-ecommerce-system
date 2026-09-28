package com.arpitha.inventory_service.service;

import java.util.UUID;

public interface StockReservationService {

    /**
     * Reserves stock for a new order. Safe to call more than once for the same order:
     * repeat calls return the original outcome without touching stock again.
     */
    ReservationResult reserveStock(Long orderId, UUID productId, Integer quantity);

    /**
     * Read-only: the outcome already stored for this order, without reserving anything.
     * Returns an IGNORED result if nothing is stored.
     */
    ReservationResult storedOutcome(Long orderId);

    /** Returns an order's reserved stock. Safe to call more than once for the same order. */
    void releaseStock(Long orderId, UUID productId);
}
