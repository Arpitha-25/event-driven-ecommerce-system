package com.arpitha.inventory_service.event.consumer;

import com.arpitha.common.filter.CorrelationIdContext;
import com.arpitha.inventory_service.event.incoming.OrderCancelledMessage;
import com.arpitha.inventory_service.event.incoming.OrderCreatedMessage;
import com.arpitha.inventory_service.event.mapper.InventoryEventMapper;
import com.arpitha.inventory_service.event.publisher.InventoryEventPublisher;
import com.arpitha.inventory_service.service.ReservationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final OrderEventHandler orderEventHandler;
    private final InventoryEventMapper inventoryEventMapper;
    private final InventoryEventPublisher inventoryEventPublisher;

    @KafkaListener(topics = "${app.kafka.topics.order-created}")
    public void onOrderCreated(OrderCreatedMessage message) {
        CorrelationIdContext.setCorrelationId(message.correlationId());
        try {
            log.info("Received OrderCreatedEvent {} for order {}", message.eventId(), message.orderId());

            // The handler's transaction commits first; the reply is published only after that.
            ReservationResult result = orderEventHandler.handleOrderCreated(message);

            switch (result.outcome()) {
                case RESERVED -> inventoryEventPublisher.publishInventoryReservedEvent(
                        inventoryEventMapper.toReservedEvent(result));
                case FAILED -> inventoryEventPublisher.publishInventoryReservationFailedEvent(
                        inventoryEventMapper.toReservationFailedEvent(result));
                case IGNORED -> log.info("No reply needed for order {}", message.orderId());
            }
        } finally {
            CorrelationIdContext.clear();
        }
    }

    @KafkaListener(topics = "${app.kafka.topics.order-cancelled}")
    public void onOrderCancelled(OrderCancelledMessage message) {
        CorrelationIdContext.setCorrelationId(message.correlationId());
        try {
            log.info("Received OrderCancelledEvent {} for order {}", message.eventId(), message.orderId());
            orderEventHandler.handleOrderCancelled(message);
        } finally {
            CorrelationIdContext.clear();
        }
    }
}
