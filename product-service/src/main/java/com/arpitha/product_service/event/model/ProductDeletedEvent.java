package com.arpitha.product_service.event.model;

import com.arpitha.common.event.BaseEvent;
import lombok.Getter;

import java.util.UUID;

@Getter
public class ProductDeletedEvent extends BaseEvent {
    private final UUID productId;
    private final String sku;

    public ProductDeletedEvent(UUID productId, String sku) {
        this.productId = productId;
        this.sku = sku;
    }
}
