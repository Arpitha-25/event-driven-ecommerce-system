package com.arpitha.inventory_service.service;

import com.arpitha.inventory_service.dto.CreateInventoryRequest;
import com.arpitha.inventory_service.dto.InventoryResponse;
import com.arpitha.inventory_service.dto.InventorySummaryResponse;
import com.arpitha.inventory_service.dto.UpdateInventoryRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface InventoryService {
    InventoryResponse createInventory(CreateInventoryRequest request);
    InventoryResponse updateInventory(UUID id, UpdateInventoryRequest request);
    void deleteInventory(UUID id);
    InventoryResponse getInventoryById(UUID id);
    InventoryResponse getInventoryByProductId(UUID productId);
    Page<InventorySummaryResponse> getAllInventory(Pageable pageable);
}
