package com.arpitha.order_service.event.publisher;

import com.arpitha.common.event.BaseEvent;
import com.arpitha.order_service.config.KafkaTopicProperties;
import com.arpitha.order_service.entity.OutboxEvent;
import com.arpitha.order_service.event.model.OrderCancelledEvent;
import com.arpitha.order_service.event.model.OrderCreatedEvent;
import com.arpitha.order_service.event.model.OrderUpdatedEvent;
import com.arpitha.order_service.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional Outbox: instead of sending to Kafka, each event is stored in outbox_events
 * inside the caller's transaction, so it is saved if and only if the order change is saved.
 * OutboxRelay does the actual publishing.
 *
 * MANDATORY fails fast if called without a transaction, because an outbox row written
 * separately from the order change would bring back the dual-write problem.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxOrderEventPublisher implements OrderEventPublisher {

    private static final String AGGREGATE_TYPE = "Order";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTopicProperties topicProperties;
    private final ObjectMapper objectMapper;

    @Override
    public void publishOrderCreatedEvent(OrderCreatedEvent event) {
        save(topicProperties.getOrderCreated(), event.getOrderId(), event);
    }

    @Override
    public void publishOrderUpdatedEvent(OrderUpdatedEvent event) {
        save(topicProperties.getOrderUpdated(), event.getOrderId(), event);
    }

    @Override
    public void publishOrderCancelledEvent(OrderCancelledEvent event) {
        save(topicProperties.getOrderCancelled(), event.getOrderId(), event);
    }

    private void save(String topic, Long orderId, BaseEvent event) {
        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(orderId.toString())
                .eventType(event.getEventType())
                .topic(topic)
                .messageKey(orderId.toString())
                .payload(toJson(event))
                .build());
    }

    private String toJson(BaseEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + event.getEventType(), e);
        }
    }
}
