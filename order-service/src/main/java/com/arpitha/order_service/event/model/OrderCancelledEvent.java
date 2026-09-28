package com.arpitha.order_service.event.model;

import com.arpitha.common.event.BaseEvent;
import lombok.Getter;

import java.util.UUID;

@Getter
public class OrderCancelledEvent extends BaseEvent {
    private final Long orderId;
    private final UUID productId;
    private final String productName;
    private final Integer quantity;
    private final Double price;
    private final String orderStatus;

    public OrderCancelledEvent(Long orderId, UUID productId, String productName, Integer quantity, Double price, String orderStatus) {
        this.orderId = orderId;
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.price = price;
        this.orderStatus = orderStatus;
    }
}
