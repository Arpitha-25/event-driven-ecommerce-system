package com.arpitha.order_service.event.publisher;

import com.arpitha.order_service.event.model.OrderCancelledEvent;
import com.arpitha.order_service.event.model.OrderCreatedEvent;
import com.arpitha.order_service.event.model.OrderUpdatedEvent;

public interface OrderEventPublisher {
    
    void publishOrderCreatedEvent(OrderCreatedEvent event);

    void publishOrderUpdatedEvent(OrderUpdatedEvent event);

    void publishOrderCancelledEvent(OrderCancelledEvent event);
}
