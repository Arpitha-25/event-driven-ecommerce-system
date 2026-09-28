package com.arpitha.inventory_service.controller;

import com.arpitha.common.dto.ApiResponse;
import com.arpitha.inventory_service.dto.CreateInventoryRequest;
import com.arpitha.inventory_service.dto.InventoryResponse;
import com.arpitha.inventory_service.dto.InventorySummaryResponse;
import com.arpitha.inventory_service.dto.UpdateInventoryRequest;
import com.arpitha.inventory_service.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InventoryResponse> createInventory(@Valid @RequestBody CreateInventoryRequest request) {
        InventoryResponse response = inventoryService.createInventory(request);
        return ApiResponse.<InventoryResponse>builder()
                .success(true)
                .message("Inventory created successfully")
                .data(response)
                .build();
    }

    @GetMapping
    public ApiResponse<Page<InventorySummaryResponse>> getAllInventory(Pageable pageable) {
        Page<InventorySummaryResponse> responses = inventoryService.getAllInventory(pageable);
        return ApiResponse.<Page<InventorySummaryResponse>>builder()
                .success(true)
                .message("Inventory fetched successfully")
                .data(responses)
                .build();
    }

    @GetMapping("/{id}")
    public ApiResponse<InventoryResponse> getInventoryById(@PathVariable UUID id) {
        InventoryResponse response = inventoryService.getInventoryById(id);
        return ApiResponse.<InventoryResponse>builder()
                .success(true)
                .message("Inventory fetched successfully")
                .data(response)
                .build();
    }

    @GetMapping("/product/{productId}")
    public ApiResponse<InventoryResponse> getInventoryByProductId(@PathVariable UUID productId) {
        InventoryResponse response = inventoryService.getInventoryByProductId(productId);
        return ApiResponse.<InventoryResponse>builder()
                .success(true)
                .message("Inventory fetched successfully")
                .data(response)
                .build();
    }

    @PutMapping("/{id}")
    public ApiResponse<InventoryResponse> updateInventory(@PathVariable UUID id, @Valid @RequestBody UpdateInventoryRequest request) {
        InventoryResponse response = inventoryService.updateInventory(id, request);
        return ApiResponse.<InventoryResponse>builder()
                .success(true)
                .message("Inventory updated successfully")
                .data(response)
                .build();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteInventory(@PathVariable UUID id) {
        inventoryService.deleteInventory(id);
        return ApiResponse.<Void>builder()
                .success(true)
                .message("Inventory deleted successfully")
                .build();
    }
}
