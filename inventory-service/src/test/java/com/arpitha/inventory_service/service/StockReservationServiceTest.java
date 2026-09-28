package com.arpitha.inventory_service.service;

import com.arpitha.inventory_service.entity.Inventory;
import com.arpitha.inventory_service.entity.StockReservation;
import com.arpitha.inventory_service.enums.InventoryStatus;
import com.arpitha.inventory_service.enums.ReservationStatus;
import com.arpitha.inventory_service.repository.InventoryRepository;
import com.arpitha.inventory_service.repository.StockReservationRepository;
import com.arpitha.inventory_service.service.impl.StockReservationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockReservationServiceTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private StockReservationRepository reservationRepository;

    @InjectMocks
    private StockReservationServiceImpl service;

    private Inventory inventory(int total, int reserved, InventoryStatus status) {
        return Inventory.builder()
                .productId(PRODUCT_ID)
                .totalQuantity(total)
                .reservedQuantity(reserved)
                .availableQuantity(total - reserved)
                .status(status)
                .build();
    }

    private StockReservation savedReservation() {
        ArgumentCaptor<StockReservation> captor = ArgumentCaptor.forClass(StockReservation.class);
        verify(reservationRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void reserveStock_ShouldReserveWhenEnoughStock() {
        Inventory inventory = inventory(10, 0, InventoryStatus.ACTIVE);
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(inventory));

        ReservationResult result = service.reserveStock(1L, PRODUCT_ID, 3);

        assertEquals(ReservationResult.Outcome.RESERVED, result.outcome());
        assertEquals(3, inventory.getReservedQuantity());
        assertEquals(7, inventory.getAvailableQuantity());
        assertEquals(ReservationStatus.RESERVED, savedReservation().getStatus());
        verify(inventoryRepository).save(inventory);
    }

    @Test
    void reserveStock_ShouldMarkOutOfStockWhenLastUnitsReserved() {
        Inventory inventory = inventory(2, 0, InventoryStatus.ACTIVE);
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(inventory));

        service.reserveStock(1L, PRODUCT_ID, 2);

        assertEquals(0, inventory.getAvailableQuantity());
        assertEquals(InventoryStatus.OUT_OF_STOCK, inventory.getStatus());
    }

    @Test
    void reserveStock_ShouldFailWhenStockIsInsufficient() {
        Inventory inventory = inventory(5, 3, InventoryStatus.ACTIVE);
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(inventory));

        ReservationResult result = service.reserveStock(1L, PRODUCT_ID, 4);

        assertEquals(ReservationResult.Outcome.FAILED, result.outcome());
        assertTrue(result.reason().contains("requested 4, available 2"));
        assertEquals(3, inventory.getReservedQuantity());
        assertEquals(ReservationStatus.REJECTED, savedReservation().getStatus());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void reserveStock_ShouldFailWhenProductHasNoInventory() {
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdForUpdate(PRODUCT_ID)).thenReturn(Optional.empty());

        ReservationResult result = service.reserveStock(1L, PRODUCT_ID, 1);

        assertEquals(ReservationResult.Outcome.FAILED, result.outcome());
        assertTrue(result.reason().startsWith("No inventory found"));
    }

    @Test
    void reserveStock_ShouldFailWhenProductIsDiscontinued() {
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdForUpdate(PRODUCT_ID))
                .thenReturn(Optional.of(inventory(0, 0, InventoryStatus.DISCONTINUED)));

        ReservationResult result = service.reserveStock(1L, PRODUCT_ID, 1);

        assertEquals(ReservationResult.Outcome.FAILED, result.outcome());
        assertTrue(result.reason().contains("discontinued"));
    }

    @Test
    void reserveStock_ShouldReplayExistingOutcomeWithoutTouchingStock() {
        StockReservation existing = StockReservation.builder()
                .orderId(1L).productId(PRODUCT_ID).quantity(3).status(ReservationStatus.RESERVED).build();
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.of(existing));

        ReservationResult result = service.reserveStock(1L, PRODUCT_ID, 3);

        assertEquals(ReservationResult.Outcome.RESERVED, result.outcome());
        verifyNoInteractions(inventoryRepository);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void reserveStock_ShouldIgnoreOrderCancelledBeforeCreationArrived() {
        StockReservation marker = StockReservation.builder()
                .orderId(1L).quantity(0).status(ReservationStatus.CANCELLED).build();
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.of(marker));

        ReservationResult result = service.reserveStock(1L, PRODUCT_ID, 3);

        assertEquals(ReservationResult.Outcome.IGNORED, result.outcome());
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void releaseStock_ShouldReturnReservedStock() {
        Inventory inventory = inventory(10, 10, InventoryStatus.OUT_OF_STOCK);
        StockReservation reservation = StockReservation.builder()
                .orderId(1L).productId(PRODUCT_ID).quantity(4).status(ReservationStatus.RESERVED).build();
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findByProductIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(inventory));

        service.releaseStock(1L, PRODUCT_ID);

        assertEquals(6, inventory.getReservedQuantity());
        assertEquals(4, inventory.getAvailableQuantity());
        assertEquals(InventoryStatus.ACTIVE, inventory.getStatus());
        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
    }

    @Test
    void releaseStock_ShouldRecordCancellationThatArrivesBeforeCreation() {
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.empty());

        service.releaseStock(1L, PRODUCT_ID);

        assertEquals(ReservationStatus.CANCELLED, savedReservation().getStatus());
        verifyNoInteractions(inventoryRepository);
    }

    @Test
    void releaseStock_ShouldDoNothingForAlreadyReleasedReservation() {
        StockReservation reservation = StockReservation.builder()
                .orderId(1L).productId(PRODUCT_ID).quantity(4).status(ReservationStatus.RELEASED).build();
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.of(reservation));

        service.releaseStock(1L, PRODUCT_ID);

        verifyNoInteractions(inventoryRepository);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void storedOutcome_ShouldReplayWithoutTouchingStock() {
        StockReservation existing = StockReservation.builder()
                .orderId(1L).productId(PRODUCT_ID).quantity(3).status(ReservationStatus.REJECTED).reason("Insufficient stock").build();
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.of(existing));

        ReservationResult result = service.storedOutcome(1L);

        assertEquals(ReservationResult.Outcome.FAILED, result.outcome());
        assertEquals("Insufficient stock", result.reason());
        verifyNoInteractions(inventoryRepository);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void storedOutcome_ShouldBeIgnoredWhenNothingIsStored() {
        when(reservationRepository.findByOrderId(1L)).thenReturn(Optional.empty());

        assertEquals(ReservationResult.Outcome.IGNORED, service.storedOutcome(1L).outcome());
        verifyNoInteractions(inventoryRepository);
        verify(reservationRepository, never()).save(any());
    }
}
