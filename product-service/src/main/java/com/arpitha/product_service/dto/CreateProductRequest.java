package com.arpitha.product_service.dto;

import com.arpitha.product_service.domain.enums.Category;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Data
@Schema(description = "Request object for creating a new product")
public class CreateProductRequest {

    @NotBlank(message = "SKU is required")
    @Size(min = 3, max = 50, message = "SKU must be between 3 and 50 characters")
    @Schema(description = "Stock Keeping Unit", example = "SKU-12345")
    private String sku;

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name cannot exceed 255 characters")
    @Schema(description = "Product Name", example = "Wireless Headphones")
    private String name;

    @Size(max = 1000, message = "Description cannot exceed 1000 characters")
    @Schema(description = "Product Description", example = "High-quality wireless headphones with noise cancellation.")
    private String description;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.01", message = "Price must be strictly positive")
    @Schema(description = "Product Price", example = "199.99")
    private BigDecimal price;

    @NotNull(message = "Category is required")
    @Schema(description = "Product Category", example = "ELECTRONICS")
    private Category category;
}
