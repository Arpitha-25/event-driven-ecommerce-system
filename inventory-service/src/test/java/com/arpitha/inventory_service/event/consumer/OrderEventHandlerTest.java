package com.arpitha.inventory_service.event.consumer;

import com.arpitha.inventory_service.event.incoming.OrderCancelledMessage;
import com.arpitha.inventory_service.event.incoming.OrderCreatedMessage;
import com.arpitha.inventory_service.idempotency.ProcessedEventStore;
import com.arpitha.inventory_service.service.ReservationResult;
import com.arpitha.inventory_service.service.StockReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventHandlerTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private ProcessedEventStore processedEventStore;

    @Mock
    private StockReservationService stockReservationService;

    @InjectMocks
    private OrderEventHandler handler;

    private final OrderCreatedMessage created = new OrderCreatedMessage("event-1", "corr-1", 7L, PRODUCT_ID, 3);
    private final OrderCancelledMessage cancelled = new OrderCancelledMessage("event-2", "corr-2", 7L, PRODUCT_ID);

    @Test
    void handleOrderCreated_ShouldReserveStockTheFirstTime() {
        ReservationResult reserved = ReservationResult.reserved(7L, PRODUCT_ID, 3);
        when(processedEventStore.markProcessed(OrderEventHandler.CREATED_CONSUMER, "event-1")).thenReturn(true);
        when(stockReservationService.reserveStock(7L, PRODUCT_ID, 3)).thenReturn(reserved);

        assertSame(reserved, handler.handleOrderCreated(created));
    }

    @Test
    void handleOrderCreated_ShouldReturnStoredOutcomeForDuplicateSoReplyCanBeResent() {
        ReservationResult stored = ReservationResult.reserved(7L, PRODUCT_ID, 3);
        when(processedEventStore.markProcessed(OrderEventHandler.CREATED_CONSUMER, "event-1")).thenReturn(false);
        when(stockReservationService.storedOutcome(7L)).thenReturn(stored);

        assertSame(stored, handler.handleOrderCreated(created));
        verify(stockReservationService, never()).reserveStock(any(), any(), any());
    }

    @Test
    void handleOrderCreated_DuplicateWithNothingStored_ShouldNeverReserve() {
        // Regression: a reused eventId for an order with no stored reservation used to reserve stock.
        when(processedEventStore.markProcessed(OrderEventHandler.CREATED_CONSUMER, "event-1")).thenReturn(false);
        when(stockReservationService.storedOutcome(7L)).thenReturn(ReservationResult.ignored(7L));

        assertEquals(ReservationResult.Outcome.IGNORED, handler.handleOrderCreated(created).outcome());
        verify(stockReservationService, never()).reserveStock(any(), any(), any());
    }

    @Test
    void handleOrderCancelled_ShouldReleaseStockTheFirstTime() {
        when(processedEventStore.markProcessed(OrderEventHandler.CANCELLED_CONSUMER, "event-2")).thenReturn(true);

        handler.handleOrderCancelled(cancelled);

        verify(stockReservationService).releaseStock(7L, PRODUCT_ID);
    }

    @Test
    void handleOrderCancelled_ShouldSkipDuplicateEvent() {
        when(processedEventStore.markProcessed(OrderEventHandler.CANCELLED_CONSUMER, "event-2")).thenReturn(false);

        handler.handleOrderCancelled(cancelled);

        verify(stockReservationService, never()).releaseStock(any(), any());
    }
}
