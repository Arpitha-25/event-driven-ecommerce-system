package com.arpitha.inventory_service.event.model;

import com.arpitha.common.event.BaseEvent;
import lombok.Getter;

import java.util.UUID;

@Getter
public class InventoryReservedEvent extends BaseEvent {
    private final Long orderId;
    private final UUID productId;
    private final Integer quantity;

    public InventoryReservedEvent(Long orderId, UUID productId, Integer quantity) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
    }
}
