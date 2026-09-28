package com.arpitha.order_service.outbox;

import com.arpitha.order_service.config.KafkaTopicProperties;
import com.arpitha.order_service.entity.OutboxEvent;
import com.arpitha.order_service.event.model.OrderCreatedEvent;
import com.arpitha.order_service.event.publisher.OutboxOrderEventPublisher;
import com.arpitha.order_service.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxOrderEventPublisherTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final OutboxOrderEventPublisher publisher;

    OutboxOrderEventPublisherTest() {
        KafkaTopicProperties topics = new KafkaTopicProperties();
        topics.setOrderCreated("order.created.v1");
        publisher = new OutboxOrderEventPublisher(repository, topics, objectMapper);
    }

    @Test
    void publishOrderCreatedEvent_ShouldStoreEventInOutboxInsteadOfSendingToKafka() throws Exception {
        UUID productId = UUID.randomUUID();
        OrderCreatedEvent event = new OrderCreatedEvent(42L, productId, "Laptop", 2, 999.0, "CREATED");
        event.setEventId("event-1");
        event.setEventType("ORDER_CREATED");
        event.setCorrelationId("corr-1");

        publisher.publishOrderCreatedEvent(event);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent saved = captor.getValue();

        assertEquals("order.created.v1", saved.getTopic());
        assertEquals("42", saved.getMessageKey());
        assertEquals("Order", saved.getAggregateType());
        assertEquals("42", saved.getAggregateId());
        assertEquals("ORDER_CREATED", saved.getEventType());
        assertNull(saved.getPublishedAt());

        JsonNode payload = objectMapper.readTree(saved.getPayload());
        assertEquals(42L, payload.get("orderId").asLong());
        assertEquals(productId.toString(), payload.get("productId").asText());
        assertEquals(2, payload.get("quantity").asInt());
        assertEquals("event-1", payload.get("eventId").asText());
        assertEquals("corr-1", payload.get("correlationId").asText());
    }
}
