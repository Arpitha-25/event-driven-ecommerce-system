package com.arpitha.order_service.outbox;

import com.arpitha.order_service.config.OutboxProperties;
import com.arpitha.order_service.entity.OutboxEvent;
import com.arpitha.order_service.repository.OutboxEventRepository;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class OutboxRelayTest {

    private OutboxEventRepository repository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxProperties properties;
    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        properties = new OutboxProperties();
        properties.setBatchSize(10);
        properties.setSendTimeoutMs(1000);
        TransactionTemplate transactionTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));
        relay = new OutboxRelay(repository, kafkaTemplate, transactionTemplate, properties);
    }

    private OutboxEvent pending(long id, String orderId) {
        return OutboxEvent.builder()
                .id(id).aggregateType("Order").aggregateId(orderId).eventType("ORDER_CREATED")
                .topic("order.created.v1").messageKey(orderId).payload("{\"orderId\":" + orderId + "}")
                .build();
    }

    private static CompletableFuture<SendResult<String, String>> sent() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> kafkaDown() {
        return CompletableFuture.failedFuture(new TimeoutException("Topic order.created.v1 not present in metadata"));
    }

    @Test
    void publishPendingEvents_ShouldSendInOrderAndMarkPublished() {
        OutboxEvent first = pending(1, "7");
        OutboxEvent second = pending(2, "8");
        when(repository.lockNextUnpublished(10)).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(sent());

        relay.publishPendingEvents();

        InOrder inOrder = inOrder(kafkaTemplate);
        inOrder.verify(kafkaTemplate).send("order.created.v1", "7", "{\"orderId\":7}");
        inOrder.verify(kafkaTemplate).send("order.created.v1", "8", "{\"orderId\":8}");
        assertNotNull(first.getPublishedAt());
        assertNotNull(second.getPublishedAt());
        assertEquals(1, first.getAttempts());
    }

    @Test
    void publishPendingEvents_ShouldStopAtFirstFailureAndKeepEventPending() {
        OutboxEvent first = pending(1, "7");
        OutboxEvent second = pending(2, "7");
        when(repository.lockNextUnpublished(10)).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(kafkaDown());

        relay.publishPendingEvents();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
        assertNull(first.getPublishedAt());
        assertEquals(1, first.getAttempts());
        assertTrue(first.getLastError().contains("not present in metadata"));
        assertNull(second.getPublishedAt());
        assertEquals(0, second.getAttempts());
    }

    @Test
    void publishPendingEvents_ShouldRetryEarlierFailureOnNextRun() {
        OutboxEvent event = pending(1, "7");
        when(repository.lockNextUnpublished(10)).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(kafkaDown(), sent());

        relay.publishPendingEvents();
        assertNull(event.getPublishedAt());

        relay.publishPendingEvents();
        assertNotNull(event.getPublishedAt());
        assertEquals(2, event.getAttempts());
        assertNull(event.getLastError());
    }

    @Test
    void publishPendingEvents_ShouldKeepDrainingWhileBatchesAreFull() {
        properties.setBatchSize(1);
        when(repository.lockNextUnpublished(1))
                .thenReturn(List.of(pending(1, "7")))
                .thenReturn(List.of(pending(2, "8")))
                .thenReturn(List.of());
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(sent());

        relay.publishPendingEvents();

        verify(repository, times(3)).lockNextUnpublished(anyInt());
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void publishPendingEvents_ShouldDoNothingWhenOutboxIsEmpty() {
        when(repository.lockNextUnpublished(10)).thenReturn(List.of());

        relay.publishPendingEvents();

        verifyNoInteractions(kafkaTemplate);
    }
}
