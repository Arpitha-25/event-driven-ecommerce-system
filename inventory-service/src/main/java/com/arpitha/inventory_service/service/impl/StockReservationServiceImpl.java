package com.arpitha.inventory_service.service.impl;

import com.arpitha.inventory_service.entity.Inventory;
import com.arpitha.inventory_service.entity.StockReservation;
import com.arpitha.inventory_service.enums.InventoryStatus;
import com.arpitha.inventory_service.enums.ReservationStatus;
import com.arpitha.inventory_service.repository.InventoryRepository;
import com.arpitha.inventory_service.repository.StockReservationRepository;
import com.arpitha.inventory_service.service.ReservationResult;
import com.arpitha.inventory_service.service.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockReservationServiceImpl implements StockReservationService {

    private final InventoryRepository inventoryRepository;
    private final StockReservationRepository reservationRepository;

    @Override
    @Transactional
    public ReservationResult reserveStock(Long orderId, UUID productId, Integer quantity) {
        if (orderId == null) {
            log.warn("Ignoring order event without an order ID");
            return ReservationResult.ignored(null);
        }

        Optional<StockReservation> existing = reservationRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            log.info("Order {} already handled (reservation {}), replaying its outcome",
                    orderId, existing.get().getStatus());
            return replay(existing.get());
        }

        Inventory inventory = null;
        String failure = null;

        if (productId == null || quantity == null || quantity < 1) {
            failure = "Order has no valid product ID or quantity";
        } else {
            inventory = inventoryRepository.findByProductIdForUpdate(productId).orElse(null);
            if (inventory == null) {
                failure = "No inventory found for product " + productId;
            } else if (inventory.getStatus() == InventoryStatus.DISCONTINUED) {
                failure = "Product " + productId + " is discontinued";
            } else if (inventory.getAvailableQuantity() < quantity) {
                failure = String.format("Insufficient stock for product %s: requested %d, available %d",
                        productId, quantity, inventory.getAvailableQuantity());
            }
        }

        if (failure != null) {
            reservationRepository.save(StockReservation.builder()
                    .orderId(orderId)
                    .productId(productId)
                    .quantity(quantity == null ? 0 : quantity)
                    .status(ReservationStatus.REJECTED)
                    .reason(failure)
                    .build());
            log.warn("Stock reservation failed for order {}: {}", orderId, failure);
            return ReservationResult.failed(orderId, productId, quantity, failure);
        }

        inventory.setReservedQuantity(inventory.getReservedQuantity() + quantity);
        inventory.setAvailableQuantity(inventory.getAvailableQuantity() - quantity);
        refreshStockStatus(inventory);
        inventoryRepository.save(inventory);

        reservationRepository.save(StockReservation.builder()
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .status(ReservationStatus.RESERVED)
                .build());

        log.info("Reserved {} unit(s) of product {} for order {} ({} left)",
                quantity, productId, orderId, inventory.getAvailableQuantity());
        return ReservationResult.reserved(orderId, productId, quantity);
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResult storedOutcome(Long orderId) {
        if (orderId == null) {
            return ReservationResult.ignored(null);
        }
        return reservationRepository.findByOrderId(orderId)
                .map(this::replay)
                .orElseGet(() -> {
                    log.warn("No stored outcome for order {}; nothing to re-send", orderId);
                    return ReservationResult.ignored(orderId);
                });
    }

    @Override
    @Transactional
    public void releaseStock(Long orderId, UUID productId) {
        if (orderId == null) {
            log.warn("Ignoring order cancellation without an order ID");
            return;
        }

        Optional<StockReservation> existing = reservationRepository.findByOrderId(orderId);

        if (existing.isEmpty()) {
            // The cancellation overtook the creation event (they travel on different topics).
            // Record it so the late creation event is ignored instead of reserving stock.
            reservationRepository.save(StockReservation.builder()
                    .orderId(orderId)
                    .productId(productId)
                    .quantity(0)
                    .status(ReservationStatus.CANCELLED)
                    .reason("Order cancelled before stock was reserved")
                    .build());
            log.info("Order {} cancelled before any reservation; recorded so it won't be reserved later", orderId);
            return;
        }

        StockReservation reservation = existing.get();
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            log.info("Nothing to release for order {} (reservation {})", orderId, reservation.getStatus());
            return;
        }

        inventoryRepository.findByProductIdForUpdate(reservation.getProductId()).ifPresent(inventory -> {
            if (inventory.getStatus() == InventoryStatus.DISCONTINUED) {
                // Discontinuing already zeroed every quantity; there is no stock to return.
                return;
            }
            inventory.setReservedQuantity(Math.max(0, inventory.getReservedQuantity() - reservation.getQuantity()));
            inventory.setAvailableQuantity(inventory.getTotalQuantity() - inventory.getReservedQuantity());
            refreshStockStatus(inventory);
            inventoryRepository.save(inventory);
        });

        reservation.setStatus(ReservationStatus.RELEASED);
        reservationRepository.save(reservation);
        log.info("Released {} unit(s) of product {} for cancelled order {}",
                reservation.getQuantity(), reservation.getProductId(), orderId);
    }

    private ReservationResult replay(StockReservation reservation) {
        return switch (reservation.getStatus()) {
            case RESERVED -> ReservationResult.reserved(
                    reservation.getOrderId(), reservation.getProductId(), reservation.getQuantity());
            case REJECTED -> ReservationResult.failed(
                    reservation.getOrderId(), reservation.getProductId(), reservation.getQuantity(),
                    reservation.getReason());
            case RELEASED, CANCELLED -> ReservationResult.ignored(reservation.getOrderId());
        };
    }

    private void refreshStockStatus(Inventory inventory) {
        if (inventory.getAvailableQuantity() == 0) {
            inventory.setStatus(InventoryStatus.OUT_OF_STOCK);
        } else if (inventory.getStatus() == InventoryStatus.OUT_OF_STOCK) {
            inventory.setStatus(InventoryStatus.ACTIVE);
        }
    }
}
