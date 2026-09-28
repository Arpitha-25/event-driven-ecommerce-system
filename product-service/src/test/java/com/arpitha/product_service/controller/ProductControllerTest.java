package com.arpitha.product_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.arpitha.common.dto.ApiResponse;
import com.arpitha.product_service.domain.enums.Category;
import com.arpitha.product_service.dto.CreateProductRequest;
import com.arpitha.product_service.dto.ProductResponse;
import com.arpitha.product_service.dto.ProductSummaryResponse;
import com.arpitha.product_service.dto.UpdateProductRequest;
import com.arpitha.product_service.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.arpitha.common.util.ValidationHelper;
import org.springframework.context.annotation.Import;

@WebMvcTest(ProductController.class)
@Import(ValidationHelper.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductService productService;

    private CreateProductRequest createRequest;
    private ProductResponse productResponse;
    private UUID productId;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        
        createRequest = new CreateProductRequest();
        createRequest.setSku("SKU-123");
        createRequest.setName("Test Product");
        createRequest.setPrice(BigDecimal.valueOf(100));
        createRequest.setCategory(Category.ELECTRONICS);

        productResponse = new ProductResponse();
        productResponse.setId(productId);
        productResponse.setSku("SKU-123");
        productResponse.setName("Test Product");
        productResponse.setPrice(BigDecimal.valueOf(100));
        productResponse.setCategory(Category.ELECTRONICS);
    }

    @Test
    void createProduct_Success() throws Exception {
        when(productService.createProduct(any(CreateProductRequest.class))).thenReturn(productResponse);

        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Product created successfully"))
                .andExpect(jsonPath("$.data.sku").value("SKU-123"));

        verify(productService).createProduct(any(CreateProductRequest.class));
    }

    @Test
    void createProduct_ValidationError() throws Exception {
        createRequest.setSku(""); // Invalid SKU

        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAllProducts_Success() throws Exception {
        Page<ProductSummaryResponse> page = new PageImpl<>(Collections.singletonList(new ProductSummaryResponse()));
        when(productService.getAllProducts(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/products")
                .param("page", "0")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Products fetched successfully"))
                .andExpect(jsonPath("$.data.content").isArray());

        verify(productService).getAllProducts(any(Pageable.class));
    }

    @Test
    void getProductById_Success() throws Exception {
        when(productService.getProductById(productId)).thenReturn(productResponse);

        mockMvc.perform(get("/api/v1/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Product fetched successfully"))
                .andExpect(jsonPath("$.data.id").value(productId.toString()));

        verify(productService).getProductById(productId);
    }

    @Test
    void updateProduct_Success() throws Exception {
        UpdateProductRequest updateRequest = new UpdateProductRequest();
        updateRequest.setName("Updated Name");
        updateRequest.setPrice(BigDecimal.valueOf(150));

        when(productService.updateProduct(eq(productId), any(UpdateProductRequest.class))).thenReturn(productResponse);

        mockMvc.perform(put("/api/v1/products/{id}", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Product updated successfully"));

        verify(productService).updateProduct(eq(productId), any(UpdateProductRequest.class));
    }

    @Test
    void deleteProduct_Success() throws Exception {
        mockMvc.perform(delete("/api/v1/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Product deleted successfully"));

        verify(productService).deleteProduct(productId);
    }
}
