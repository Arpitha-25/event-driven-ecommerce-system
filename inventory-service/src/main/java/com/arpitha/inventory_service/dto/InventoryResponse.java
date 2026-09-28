package com.arpitha.inventory_service.dto;

import com.arpitha.inventory_service.enums.InventoryStatus;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
public class InventoryResponse {
    private UUID id;
    private UUID productId;
    private String sku;
    private Integer totalQuantity;
    private Integer reservedQuantity;
    private Integer availableQuantity;
    private InventoryStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
