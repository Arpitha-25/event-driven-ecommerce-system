package com.arpitha.order_service.event.mapper;

import com.arpitha.common.event.EventMetadataFactory;
import com.arpitha.common.event.EventType;
import com.arpitha.order_service.entity.Order;
import com.arpitha.order_service.event.model.OrderCancelledEvent;
import com.arpitha.order_service.event.model.OrderCreatedEvent;
import com.arpitha.order_service.event.model.OrderUpdatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderEventMapper {

    private final EventMetadataFactory eventMetadataFactory;

    public OrderCreatedEvent toCreatedEvent(Order order) {
        OrderCreatedEvent event = new OrderCreatedEvent(
                order.getId(),
                order.getProductId(),
                order.getProductName(),
                order.getQuantity(),
                order.getPrice(),
                order.getStatus().name()
        );
        return eventMetadataFactory.populateMetadata(event, EventType.ORDER_CREATED);
    }

    public OrderUpdatedEvent toUpdatedEvent(Order order) {
        OrderUpdatedEvent event = new OrderUpdatedEvent(
                order.getId(),
                order.getProductId(),
                order.getProductName(),
                order.getQuantity(),
                order.getPrice(),
                order.getStatus().name()
        );
        return eventMetadataFactory.populateMetadata(event, EventType.ORDER_UPDATED);
    }

    public OrderCancelledEvent toCancelledEvent(Order order) {
        OrderCancelledEvent event = new OrderCancelledEvent(
                order.getId(),
                order.getProductId(),
                order.getProductName(),
                order.getQuantity(),
                order.getPrice(),
                order.getStatus().name()
        );
        return eventMetadataFactory.populateMetadata(event, EventType.ORDER_CANCELLED);
    }
}
