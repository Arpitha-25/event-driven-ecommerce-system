package com.arpitha.inventory_service.event.model;

import com.arpitha.common.event.BaseEvent;
import lombok.Getter;

import java.util.UUID;

@Getter
public class InventoryReservationFailedEvent extends BaseEvent {
    private final Long orderId;
    private final UUID productId;
    private final Integer quantity;
    private final String reason;

    public InventoryReservationFailedEvent(Long orderId, UUID productId, Integer quantity, String reason) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.reason = reason;
    }
}
