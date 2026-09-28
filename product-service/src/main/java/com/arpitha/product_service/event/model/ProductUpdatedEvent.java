package com.arpitha.product_service.event.model;

import com.arpitha.common.event.BaseEvent;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
public class ProductUpdatedEvent extends BaseEvent {
    private final UUID productId;
    private final String sku;
    private final String name;
    private final BigDecimal price;
    private final String status;

    public ProductUpdatedEvent(UUID productId, String sku, String name, BigDecimal price, String status) {
        this.productId = productId;
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.status = status;
    }
}
