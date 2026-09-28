package com.arpitha.inventory_service.dto;

import com.arpitha.inventory_service.enums.InventoryStatus;
import lombok.Data;
import java.util.UUID;

@Data
public class InventorySummaryResponse {
    private UUID productId;
    private String sku;
    private Integer availableQuantity;
    private InventoryStatus status;
}
