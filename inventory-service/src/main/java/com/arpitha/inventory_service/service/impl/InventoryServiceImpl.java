package com.arpitha.inventory_service.service.impl;

import com.arpitha.inventory_service.dto.CreateInventoryRequest;
import com.arpitha.inventory_service.dto.InventoryResponse;
import com.arpitha.inventory_service.dto.InventorySummaryResponse;
import com.arpitha.inventory_service.dto.UpdateInventoryRequest;
import com.arpitha.inventory_service.entity.Inventory;
import com.arpitha.inventory_service.enums.InventoryStatus;
import com.arpitha.inventory_service.exception.DuplicateInventoryException;
import com.arpitha.inventory_service.exception.InvalidInventoryStateException;
import com.arpitha.inventory_service.exception.InventoryNotFoundException;
import com.arpitha.inventory_service.mapper.InventoryMapper;
import com.arpitha.inventory_service.repository.InventoryRepository;
import com.arpitha.inventory_service.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryMapper inventoryMapper;

    @Override
    @Transactional
    public InventoryResponse createInventory(CreateInventoryRequest request) {
        log.info("Creating inventory for product ID: {}", request.getProductId());
        
        if (inventoryRepository.existsByProductId(request.getProductId())) {
            log.warn("Inventory creation failed. Product ID already exists: {}", request.getProductId());
            throw new DuplicateInventoryException("Inventory already exists for product ID: " + request.getProductId());
        }
        
        if (inventoryRepository.existsBySku(request.getSku())) {
            log.warn("Inventory creation failed. SKU already exists: {}", request.getSku());
            throw new DuplicateInventoryException("Inventory already exists for SKU: " + request.getSku());
        }

        Inventory inventory = inventoryMapper.toEntity(request);
        inventory.setReservedQuantity(0);
        inventory.setAvailableQuantity(inventory.getTotalQuantity());
        inventory.setStatus(inventory.getAvailableQuantity() > 0 ? InventoryStatus.ACTIVE : InventoryStatus.OUT_OF_STOCK);

        Inventory savedInventory = inventoryRepository.save(inventory);
        log.info("Inventory created successfully with ID: {}", savedInventory.getId());
        
        return inventoryMapper.toResponse(savedInventory);
    }

    @Override
    @Transactional
    public InventoryResponse updateInventory(UUID id, UpdateInventoryRequest request) {
        log.info("Updating inventory with ID: {}", id);
        
        Inventory inventory = inventoryRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Inventory lookup failed. ID not found: {}", id);
                    return new InventoryNotFoundException("Inventory not found with ID: " + id);
                });
                
        inventoryMapper.updateEntityFromRequest(request, inventory);
        
        if (inventory.getTotalQuantity() < inventory.getReservedQuantity()) {
            throw new InvalidInventoryStateException("Total quantity cannot be less than reserved quantity");
        }
        
        inventory.setAvailableQuantity(inventory.getTotalQuantity() - inventory.getReservedQuantity());
        
        if (request.getStatus() == null) {
            inventory.setStatus(inventory.getAvailableQuantity() > 0 ? InventoryStatus.ACTIVE : InventoryStatus.OUT_OF_STOCK);
        }

        Inventory updatedInventory = inventoryRepository.save(inventory);
        log.info("Inventory updated successfully with ID: {}", updatedInventory.getId());
        
        return inventoryMapper.toResponse(updatedInventory);
    }

    @Override
    @Transactional
    public void deleteInventory(UUID id) {
        log.info("Deleting inventory with ID: {}", id);
        Inventory inventory = inventoryRepository.findById(id)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory not found with ID: " + id));
                
        inventory.setStatus(InventoryStatus.DISCONTINUED);
        inventory.setAvailableQuantity(0);
        inventory.setTotalQuantity(0);
        inventory.setReservedQuantity(0);
        inventoryRepository.save(inventory);
        
        log.info("Inventory marked as discontinued with ID: {}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryResponse getInventoryById(UUID id) {
        log.info("Fetching inventory with ID: {}", id);
        Inventory inventory = inventoryRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Inventory lookup failed. ID not found: {}", id);
                    return new InventoryNotFoundException("Inventory not found with ID: " + id);
                });
        return inventoryMapper.toResponse(inventory);
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryResponse getInventoryByProductId(UUID productId) {
        log.info("Fetching inventory for product ID: {}", productId);
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> {
                    log.warn("Inventory lookup failed. Product ID not found: {}", productId);
                    return new InventoryNotFoundException("Inventory not found for product ID: " + productId);
                });
        return inventoryMapper.toResponse(inventory);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InventorySummaryResponse> getAllInventory(Pageable pageable) {
        log.info("Fetching all inventory with pagination");
        return inventoryRepository.findAll(pageable)
                .map(inventoryMapper::toSummaryResponse);
    }
}
