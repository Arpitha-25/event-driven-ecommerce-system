package com.arpitha.product_service.service;

import com.arpitha.product_service.domain.enums.Category;
import com.arpitha.product_service.domain.enums.ProductStatus;
import com.arpitha.product_service.dto.CreateProductRequest;
import com.arpitha.product_service.dto.ProductResponse;
import com.arpitha.product_service.dto.ProductSummaryResponse;
import com.arpitha.product_service.dto.UpdateProductRequest;
import com.arpitha.product_service.entity.Product;
import com.arpitha.product_service.exception.DuplicateProductException;
import com.arpitha.product_service.exception.InvalidProductStateException;
import com.arpitha.product_service.exception.ProductNotFoundException;
import com.arpitha.product_service.mapper.ProductMapper;
import com.arpitha.product_service.repository.ProductRepository;
import com.arpitha.product_service.service.impl.ProductServiceImpl;
import com.arpitha.product_service.event.mapper.ProductEventMapper;
import com.arpitha.product_service.event.publisher.ProductEventPublisher;
import com.arpitha.product_service.event.model.ProductCreatedEvent;
import com.arpitha.product_service.event.model.ProductUpdatedEvent;
import com.arpitha.product_service.event.model.ProductDeletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductEventPublisher productEventPublisher;

    @Mock
    private ProductEventMapper productEventMapper;

    @InjectMocks
    private ProductServiceImpl productService;

    private Product product;
    private CreateProductRequest createRequest;
    private ProductResponse response;

    @BeforeEach
    void setUp() {
        product = new Product();
        product.setId(UUID.randomUUID());
        product.setSku("SKU-123");
        product.setName("Test Product");
        product.setPrice(BigDecimal.valueOf(100));
        product.setStatus(ProductStatus.ACTIVE);

        createRequest = new CreateProductRequest();
        createRequest.setSku("SKU-123");
        createRequest.setName("Test Product");
        createRequest.setPrice(BigDecimal.valueOf(100));
        createRequest.setCategory(Category.ELECTRONICS);

        response = new ProductResponse();
        response.setId(product.getId());
        response.setSku("SKU-123");
        response.setName("Test Product");
    }

    @Test
    void createProduct_Success() {
        when(productRepository.existsBySku(anyString())).thenReturn(false);
        when(productRepository.existsByName(anyString())).thenReturn(false);
        when(productMapper.toEntity(createRequest)).thenReturn(product);
        when(productRepository.save(any(Product.class))).thenReturn(product);
        when(productMapper.toResponse(product)).thenReturn(response);

        when(productEventMapper.toCreatedEvent(any(Product.class), any())).thenReturn(mock(ProductCreatedEvent.class));

        ProductResponse result = productService.createProduct(createRequest);

        assertNotNull(result);
        assertEquals("Test Product", result.getName());
        verify(productRepository).save(any(Product.class));
        verify(productEventPublisher).publish(any(ProductCreatedEvent.class));
    }

    @Test
    void createProduct_ThrowsDuplicateSkuException() {
        when(productRepository.existsBySku("SKU-123")).thenReturn(true);

        assertThrows(DuplicateProductException.class, () -> productService.createProduct(createRequest));
        verify(productRepository, never()).save(any(Product.class));
    }
    
    @Test
    void createProduct_ThrowsDuplicateNameException() {
        when(productRepository.existsBySku("SKU-123")).thenReturn(false);
        when(productRepository.existsByName("Test Product")).thenReturn(true);

        assertThrows(DuplicateProductException.class, () -> productService.createProduct(createRequest));
        verify(productRepository, never()).save(any(Product.class));
    }
    
    @Test
    void createProduct_ThrowsIllegalArgumentExceptionForNegativePrice() {
        when(productRepository.existsBySku("SKU-123")).thenReturn(false);
        when(productRepository.existsByName("Test Product")).thenReturn(false);
        createRequest.setPrice(BigDecimal.valueOf(-10));

        assertThrows(IllegalArgumentException.class, () -> productService.createProduct(createRequest));
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void getProductById_Success() {
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productMapper.toResponse(product)).thenReturn(response);

        ProductResponse result = productService.getProductById(product.getId());

        assertNotNull(result);
        assertEquals(response.getId(), result.getId());
    }

    @Test
    void getProductById_ThrowsNotFoundException() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class, () -> productService.getProductById(id));
    }
    
    @Test
    void getAllProducts_Success() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Product> page = new PageImpl<>(Collections.singletonList(product));
        when(productRepository.findAll(pageable)).thenReturn(page);
        when(productMapper.toSummaryResponse(product)).thenReturn(new ProductSummaryResponse());

        Page<ProductSummaryResponse> result = productService.getAllProducts(pageable);

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
    }

    @Test
    void updateProduct_Success() {
        UpdateProductRequest updateRequest = new UpdateProductRequest();
        updateRequest.setName("Updated Product");
        updateRequest.setPrice(BigDecimal.valueOf(150));

        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.existsByName("Updated Product")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenReturn(product);
        when(productMapper.toResponse(product)).thenReturn(response);

        when(productEventMapper.toUpdatedEvent(any(Product.class), any())).thenReturn(mock(ProductUpdatedEvent.class));

        ProductResponse result = productService.updateProduct(product.getId(), updateRequest);

        assertNotNull(result);
        verify(productMapper).updateEntityFromRequest(updateRequest, product);
        verify(productEventPublisher).publish(any(ProductUpdatedEvent.class));
    }
    
    @Test
    void updateProduct_ThrowsInvalidStateExceptionForDiscontinuedProduct() {
        UpdateProductRequest updateRequest = new UpdateProductRequest();
        product.setStatus(ProductStatus.DISCONTINUED);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        assertThrows(InvalidProductStateException.class, () -> productService.updateProduct(product.getId(), updateRequest));
    }

    @Test
    void deleteProduct_Success() {
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenReturn(product);

        when(productEventMapper.toDeletedEvent(any(), any())).thenReturn(mock(ProductDeletedEvent.class));

        productService.deleteProduct(product.getId());

        verify(productRepository).save(product);
        assertEquals(ProductStatus.DISCONTINUED, product.getStatus());
        verify(productEventPublisher).publish(any(ProductDeletedEvent.class));
    }
}
