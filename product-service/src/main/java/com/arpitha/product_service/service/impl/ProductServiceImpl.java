package com.arpitha.product_service.service.impl;

import com.arpitha.product_service.config.CacheConfig;
import com.arpitha.product_service.domain.enums.ProductStatus;
import com.arpitha.product_service.dto.CreateProductRequest;
import com.arpitha.product_service.dto.ProductResponse;
import com.arpitha.product_service.dto.ProductSummaryResponse;
import com.arpitha.product_service.dto.UpdateProductRequest;
import com.arpitha.product_service.entity.Product;
import com.arpitha.product_service.exception.DuplicateProductException;
import com.arpitha.product_service.exception.InvalidProductStateException;
import com.arpitha.common.filter.CorrelationIdContext;
import com.arpitha.product_service.exception.ProductNotFoundException;
import com.arpitha.product_service.mapper.ProductMapper;
import com.arpitha.product_service.repository.ProductRepository;
import com.arpitha.product_service.service.ProductService;
import com.arpitha.product_service.event.mapper.ProductEventMapper;
import com.arpitha.product_service.event.publisher.ProductEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Slf4j
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;
    private final ProductEventPublisher productEventPublisher;
    private final ProductEventMapper productEventMapper;

    public ProductServiceImpl(ProductRepository productRepository, 
                              ProductMapper productMapper,
                              ProductEventPublisher productEventPublisher,
                              ProductEventMapper productEventMapper) {
        this.productRepository = productRepository;
        this.productMapper = productMapper;
        this.productEventPublisher = productEventPublisher;
        this.productEventMapper = productEventMapper;
    }

    @Override
    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        log.info("Creating product with SKU: {}", request.getSku());
        
        if (productRepository.existsBySku(request.getSku())) {
            log.warn("Product creation failed. SKU already exists: {}", request.getSku());
            throw new DuplicateProductException("Product with SKU " + request.getSku() + " already exists.");
        }

        if (productRepository.existsByName(request.getName())) {
            log.warn("Product creation failed. Name already exists: {}", request.getName());
            throw new DuplicateProductException("Product with name " + request.getName() + " already exists.");
        }

        if (request.getPrice() != null && request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Product creation failed. Price must be positive.");
            throw new IllegalArgumentException("Price must be strictly positive.");
        }

        Product product = productMapper.toEntity(request);
        product.setStatus(ProductStatus.ACTIVE); // Default status
        Product savedProduct = productRepository.save(product);

        log.info("Product created successfully with ID: {}", savedProduct.getId());

        // TODO: Future Improvement: Replace direct Kafka publishing with the Transactional Outbox Pattern to guarantee atomic consistency between PostgreSQL and Kafka.
        productEventPublisher.publish(
                productEventMapper.toCreatedEvent(savedProduct, CorrelationIdContext.getCorrelationId())
        );

        return productMapper.toResponse(savedProduct);
    }

    /** Served from Redis after the first read; a missing product is not cached. */
    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.PRODUCTS, key = "#id")
    public ProductResponse getProductById(UUID id) {
        log.info("Fetching product with ID: {}", id);
        Product product = findProductById(id);
        return productMapper.toResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductSummaryResponse> getAllProducts(Pageable pageable) {
        log.info("Fetching all products with pagination");
        return productRepository.findAll(pageable)
                .map(productMapper::toSummaryResponse);
    }

    /** Removes the cached copy once the update has committed. */
    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.PRODUCTS, key = "#id")
    public ProductResponse updateProduct(UUID id, UpdateProductRequest request) {
        log.info("Updating product with ID: {}", id);
        Product product = findProductById(id);

        if (product.getStatus() == ProductStatus.DISCONTINUED) {
            log.warn("Product update failed. Cannot update discontinued product ID: {}", id);
            throw new InvalidProductStateException("Cannot update a discontinued product.");
        }

        if (request.getName() != null && !request.getName().equals(product.getName())) {
            if (productRepository.existsByName(request.getName())) {
                log.warn("Product update failed. Name already exists: {}", request.getName());
                throw new DuplicateProductException("Product with name " + request.getName() + " already exists.");
            }
        }

        if (request.getPrice() != null && request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Product update failed. Price must be positive.");
            throw new IllegalArgumentException("Price must be strictly positive.");
        }

        productMapper.updateEntityFromRequest(request, product);
        
        if (request.getStatus() != null) {
            product.setStatus(request.getStatus());
        }
        
        Product updatedProduct = productRepository.save(product);
        log.info("Product updated successfully with ID: {}", id);

        // TODO: Future Improvement: Replace direct Kafka publishing with the Transactional Outbox Pattern to guarantee atomic consistency between PostgreSQL and Kafka.
        productEventPublisher.publish(
                productEventMapper.toUpdatedEvent(updatedProduct, CorrelationIdContext.getCorrelationId())
        );

        return productMapper.toResponse(updatedProduct);
    }

    /** Removes the cached copy once the delete (marking it discontinued) has committed. */
    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.PRODUCTS, key = "#id")
    public void deleteProduct(UUID id) {
        log.info("Deleting product with ID: {}", id);
        Product product = findProductById(id);
        
        if (product.getStatus() == ProductStatus.DISCONTINUED) {
            log.warn("Product is already discontinued. ID: {}", id);
            return;
        }
        
        product.setStatus(ProductStatus.DISCONTINUED);
        Product savedProduct = productRepository.save(product);
        log.info("Product marked as discontinued with ID: {}", id);

        // TODO: Future Improvement: Replace direct Kafka publishing with the Transactional Outbox Pattern to guarantee atomic consistency between PostgreSQL and Kafka.
        productEventPublisher.publish(
                productEventMapper.toDeletedEvent(savedProduct, CorrelationIdContext.getCorrelationId())
        );
    }

    private Product findProductById(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Product lookup failed. ID not found: {}", id);
                    return new ProductNotFoundException("Product with ID " + id + " not found.");
                });
    }
}
