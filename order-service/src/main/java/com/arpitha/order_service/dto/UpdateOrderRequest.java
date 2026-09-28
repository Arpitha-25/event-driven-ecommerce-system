package com.arpitha.order_service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "Request payload for updating an existing order")
public class UpdateOrderRequest {

    @Schema(description = "Name of the product", example = "MacBook Pro M3")
    private String productName;

    @Schema(description = "Quantity of the product", example = "3", minimum = "1")
    @Min(value = 1, message = "Quantity must be greater than 0")
    private Integer quantity;

    @Schema(description = "Price per unit", example = "2800.0", minimum = "1")
    @Min(value = 1, message = "Price must be greater than 0")
    private Double price;

    @Schema(description = "Status of the order", example = "CREATED")
    private String status;
}
