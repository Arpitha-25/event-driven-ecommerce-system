package com.arpitha.product_service.dto;

import com.arpitha.product_service.domain.enums.Category;
import com.arpitha.product_service.domain.enums.ProductStatus;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Schema(description = "Response object representing a product")
public class ProductResponse {
    @Schema(description = "Product ID", example = "123e4567-e89b-12d3-a456-426614174000")
    private UUID id;
    @Schema(description = "Stock Keeping Unit", example = "SKU-12345")
    private String sku;
    @Schema(description = "Product Name", example = "Wireless Headphones")
    private String name;
    @Schema(description = "Product Description", example = "High-quality wireless headphones with noise cancellation.")
    private String description;
    @Schema(description = "Product Price", example = "199.99")
    private BigDecimal price;
    @Schema(description = "Product Category", example = "ELECTRONICS")
    private Category category;
    @Schema(description = "Product Status", example = "ACTIVE")
    private ProductStatus status;
    @Schema(description = "Creation timestamp")
    private Instant createdAt;
    @Schema(description = "Last update timestamp")
    private Instant updatedAt;
}
