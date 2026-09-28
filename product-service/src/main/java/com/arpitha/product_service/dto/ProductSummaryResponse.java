package com.arpitha.product_service.dto;

import com.arpitha.product_service.domain.enums.Category;
import com.arpitha.product_service.domain.enums.ProductStatus;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Schema(description = "Summary response object representing a product in a list")
public class ProductSummaryResponse {
    @Schema(description = "Product ID", example = "123e4567-e89b-12d3-a456-426614174000")
    private UUID id;
    @Schema(description = "Product Name", example = "Wireless Headphones")
    private String name;
    @Schema(description = "Product Price", example = "199.99")
    private BigDecimal price;
    @Schema(description = "Product Category", example = "ELECTRONICS")
    private Category category;
    @Schema(description = "Product Status", example = "ACTIVE")
    private ProductStatus status;
}
