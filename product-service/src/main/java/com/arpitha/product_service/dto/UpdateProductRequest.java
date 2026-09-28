package com.arpitha.product_service.dto;

import com.arpitha.product_service.domain.enums.Category;
import com.arpitha.product_service.domain.enums.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Data
@Schema(description = "Request object for updating an existing product")
public class UpdateProductRequest {

    @Size(max = 255, message = "Name cannot exceed 255 characters")
    @Schema(description = "Product Name", example = "Upgraded Wireless Headphones")
    private String name;

    @Size(max = 1000, message = "Description cannot exceed 1000 characters")
    @Schema(description = "Product Description", example = "Even better noise cancellation.")
    private String description;

    @DecimalMin(value = "0.01", message = "Price must be strictly positive")
    @Schema(description = "Product Price", example = "249.99")
    private BigDecimal price;

    @Schema(description = "Product Category", example = "ELECTRONICS")
    private Category category;

    @Schema(description = "Product Status", example = "ACTIVE")
    private ProductStatus status;
}
