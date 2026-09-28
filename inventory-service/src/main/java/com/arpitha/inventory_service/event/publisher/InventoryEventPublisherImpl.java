package com.arpitha.inventory_service.event.publisher;

import com.arpitha.inventory_service.config.KafkaTopicProperties;
import com.arpitha.inventory_service.event.model.InventoryReservationFailedEvent;
import com.arpitha.inventory_service.event.model.InventoryReservedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends synchronously: if Kafka rejects the event, the exception fails the listener,
 * the incoming order event is retried, and the idempotent reservation replays the same result.
 */
@Component
@RequiredArgsConstructor
public class InventoryEventPublisherImpl implements InventoryEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicProperties topicProperties;

    @Override
    public void publishInventoryReservedEvent(InventoryReservedEvent event) {
        kafkaTemplate.send(topicProperties.getInventoryReserved(), event.getOrderId().toString(), event).join();
    }

    @Override
    public void publishInventoryReservationFailedEvent(InventoryReservationFailedEvent event) {
        kafkaTemplate.send(topicProperties.getInventoryReservationFailed(), event.getOrderId().toString(), event).join();
    }
}
