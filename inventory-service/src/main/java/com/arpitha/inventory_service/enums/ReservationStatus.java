package com.arpitha.inventory_service.enums;

public enum ReservationStatus {
    /** Stock is held for the order. */
    RESERVED,
    /** The order could not be served (no inventory, discontinued, or not enough stock). */
    REJECTED,
    /** The order was cancelled and its reserved stock returned. */
    RELEASED,
    /** The order was cancelled before its creation event arrived; nothing was reserved. */
    CANCELLED
}
