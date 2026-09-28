package com.arpitha.product_service.controller;

import com.arpitha.common.dto.ApiResponse;
import com.arpitha.product_service.dto.CreateProductRequest;
import com.arpitha.product_service.dto.ProductResponse;
import com.arpitha.product_service.dto.ProductSummaryResponse;
import com.arpitha.product_service.dto.UpdateProductRequest;
import com.arpitha.product_service.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Product Management", description = "Endpoints for managing products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new product", description = "Adds a new product to the catalog")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Product created successfully", content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input or duplicate product", content = @Content)
    })
    public ApiResponse<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse response = productService.createProduct(request);
        return ApiResponse.<ProductResponse>builder()
                .success(true)
                .message("Product created successfully")
                .data(response)
                .build();
    }

    @GetMapping
    @Operation(summary = "Get all products", description = "Returns a paginated list of product summaries")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Products fetched successfully", content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    public ApiResponse<Page<ProductSummaryResponse>> getAllProducts(Pageable pageable) {
        Page<ProductSummaryResponse> responses = productService.getAllProducts(pageable);
        return ApiResponse.<Page<ProductSummaryResponse>>builder()
                .success(true)
                .message("Products fetched successfully")
                .data(responses)
                .build();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product by ID", description = "Returns the details of a specific product by its ID")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Product fetched successfully", content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found", content = @Content)
    })
    public ApiResponse<ProductResponse> getProductById(@PathVariable UUID id) {
        ProductResponse response = productService.getProductById(id);
        return ApiResponse.<ProductResponse>builder()
                .success(true)
                .message("Product fetched successfully")
                .data(response)
                .build();
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update product", description = "Updates an existing product's details")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Product updated successfully", content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid input", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found", content = @Content)
    })
    public ApiResponse<ProductResponse> updateProduct(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        ProductResponse response = productService.updateProduct(id, request);
        return ApiResponse.<ProductResponse>builder()
                .success(true)
                .message("Product updated successfully")
                .data(response)
                .build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete product", description = "Marks a product as discontinued")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Product deleted successfully", content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Product not found", content = @Content)
    })
    public ApiResponse<Void> deleteProduct(@PathVariable UUID id) {
        productService.deleteProduct(id);
        return ApiResponse.<Void>builder()
                .success(true)
                .message("Product deleted successfully")
                .build();
    }
}
