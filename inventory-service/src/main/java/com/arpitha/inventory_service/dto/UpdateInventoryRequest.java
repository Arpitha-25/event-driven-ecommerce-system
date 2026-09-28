package com.arpitha.inventory_service.dto;

import com.arpitha.inventory_service.enums.InventoryStatus;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class UpdateInventoryRequest {

    @PositiveOrZero(message = "Total quantity must be positive or zero")
    private Integer totalQuantity;

    private InventoryStatus status;
}
