package com.arpitha.order_service.event.consumer;

import com.arpitha.order_service.event.incoming.InventoryReservationFailedMessage;
import com.arpitha.order_service.event.incoming.InventoryReservedMessage;
import com.arpitha.order_service.idempotency.ProcessedEventStore;
import com.arpitha.order_service.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryEventHandlerTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private ProcessedEventStore processedEventStore;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private InventoryEventHandler handler;

    private final InventoryReservedMessage reserved =
            new InventoryReservedMessage("event-1", "corr-1", 7L, PRODUCT_ID, 2);
    private final InventoryReservationFailedMessage failed =
            new InventoryReservationFailedMessage("event-2", "corr-2", 8L, PRODUCT_ID, 2, "Insufficient stock");

    @Test
    void handleInventoryReserved_ShouldConfirmOrderTheFirstTime() {
        when(processedEventStore.markProcessed(InventoryEventHandler.RESERVED_CONSUMER, "event-1")).thenReturn(true);

        handler.handleInventoryReserved(reserved);

        verify(orderService).confirmOrder(7L);
    }

    @Test
    void handleInventoryReserved_ShouldSkipDuplicateEvent() {
        when(processedEventStore.markProcessed(InventoryEventHandler.RESERVED_CONSUMER, "event-1")).thenReturn(false);

        handler.handleInventoryReserved(reserved);

        verifyNoInteractions(orderService);
    }

    @Test
    void handleInventoryReservationFailed_ShouldRejectOrderTheFirstTime() {
        when(processedEventStore.markProcessed(InventoryEventHandler.FAILED_CONSUMER, "event-2")).thenReturn(true);

        handler.handleInventoryReservationFailed(failed);

        verify(orderService).rejectOrder(8L, "Insufficient stock");
    }

    @Test
    void handleInventoryReservationFailed_ShouldSkipDuplicateEvent() {
        when(processedEventStore.markProcessed(InventoryEventHandler.FAILED_CONSUMER, "event-2")).thenReturn(false);

        handler.handleInventoryReservationFailed(failed);

        verify(orderService, never()).rejectOrder(anyLong(), anyString());
    }

    @Test
    void handlers_ShouldTrackEachTopicSeparately() {
        when(processedEventStore.markProcessed(anyString(), anyString())).thenReturn(true);

        handler.handleInventoryReserved(reserved);
        handler.handleInventoryReservationFailed(failed);

        verify(processedEventStore).markProcessed("order-service:inventory.reserved.v1", "event-1");
        verify(processedEventStore).markProcessed("order-service:inventory.reservation-failed.v1", "event-2");
    }
}
