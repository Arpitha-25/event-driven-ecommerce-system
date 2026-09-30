package com.arpitha.product_service.cache;

import com.arpitha.common.util.TimeZoneNormalizer;
import com.arpitha.product_service.domain.enums.Category;
import com.arpitha.product_service.domain.enums.ProductStatus;
import com.arpitha.product_service.dto.CreateProductRequest;
import com.arpitha.product_service.dto.ProductResponse;
import com.arpitha.product_service.dto.UpdateProductRequest;
import com.arpitha.product_service.event.publisher.ProductEventPublisher;
import com.arpitha.product_service.exception.DuplicateProductException;
import com.arpitha.product_service.exception.ProductNotFoundException;
import com.arpitha.product_service.repository.ProductRepository;
import com.arpitha.product_service.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The product cache against real PostgreSQL and Redis. A spy on the repository counts the
 * database reads, so each test can tell whether a read came from Redis or from PostgreSQL.
 * Skipped automatically when Docker isn't available.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ProductCacheIntegrationTest {

    static {
        // Same as main(): PostgreSQL rejects legacy zone IDs such as "Asia/Calcutta".
        TimeZoneNormalizer.normalizeDefault();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private ProductService productService;

    @SpyBean
    private ProductRepository productRepository;

    @Autowired
    private StringRedisTemplate redis;

    /** No Kafka in this test; publishing isn't what's being tested. */
    @MockBean
    private ProductEventPublisher productEventPublisher;

    @Test
    void secondRead_IsServedFromRedis_WithoutTouchingTheDatabase() {
        UUID id = createProduct();

        ProductResponse first = productService.getProductById(id);
        ProductResponse second = productService.getProductById(id);

        verify(productRepository, times(1)).findById(id);
        assertEquals(first, second, "the cached copy is identical, including price, enums and timestamps");
    }

    @Test
    void cachedEntry_IsJsonInRedis_WithTheConfiguredExpiry() {
        UUID id = createProduct();
        productService.getProductById(id);

        String key = "product-service:products::" + id;
        String json = redis.opsForValue().get(key);
        assertNotNull(json, "entry stored under " + key);
        assertTrue(json.contains("\"id\":\"" + id + "\""), json);
        Long ttl = redis.getExpire(key);
        assertTrue(ttl != null && ttl > 0 && ttl <= 600, "expires within 10 minutes, ttl=" + ttl);
    }

    @Test
    void update_EvictsTheEntry_SoTheNextReadSeesTheChange() {
        UUID id = createProduct();
        productService.getProductById(id);

        UpdateProductRequest update = new UpdateProductRequest();
        update.setName("Renamed " + id);
        update.setPrice(new BigDecimal("12.34"));
        productService.updateProduct(id, update);

        assertFalse(Boolean.TRUE.equals(redis.hasKey("product-service:products::" + id)), "evicted after the update");
        ProductResponse afterUpdate = productService.getProductById(id);
        assertEquals("Renamed " + id, afterUpdate.getName());
        assertEquals(0, new BigDecimal("12.34").compareTo(afterUpdate.getPrice()));
    }

    @Test
    void delete_EvictsTheEntry_SoTheNextReadSeesItDiscontinued() {
        UUID id = createProduct();
        assertEquals(ProductStatus.ACTIVE, productService.getProductById(id).getStatus());

        productService.deleteProduct(id);

        assertEquals(ProductStatus.DISCONTINUED, productService.getProductById(id).getStatus());
    }

    @Test
    void failedUpdate_RollsBack_AndLeavesTheCachedEntryInPlace() {
        UUID taken = createProduct();
        UUID id = createProduct();
        ProductResponse cached = productService.getProductById(id);
        String takenName = productService.getProductById(taken).getName();

        UpdateProductRequest clash = new UpdateProductRequest();
        clash.setName(takenName);
        assertThrows(DuplicateProductException.class, () -> productService.updateProduct(id, clash));

        // The database didn't change, so the cached copy is still correct and still used.
        assertTrue(Boolean.TRUE.equals(redis.hasKey("product-service:products::" + id)));
        clearInvocations(productRepository);
        assertEquals(cached, productService.getProductById(id));
        verify(productRepository, never()).findById(id);
    }

    @Test
    void missingProduct_IsNotCached() {
        UUID missing = UUID.randomUUID();

        assertThrows(ProductNotFoundException.class, () -> productService.getProductById(missing));
        assertThrows(ProductNotFoundException.class, () -> productService.getProductById(missing));

        verify(productRepository, times(2)).findById(missing);
        assertFalse(Boolean.TRUE.equals(redis.hasKey("product-service:products::" + missing)));
    }

    private UUID createProduct() {
        String unique = UUID.randomUUID().toString();
        CreateProductRequest request = new CreateProductRequest();
        request.setSku("CACHE-" + unique);
        request.setName("Cache test " + unique);
        request.setDescription("created by ProductCacheIntegrationTest");
        request.setPrice(new BigDecimal("99.99"));
        request.setCategory(Category.ELECTRONICS);
        UUID id = productService.createProduct(request).getId();
        clearInvocations(productRepository);
        return id;
    }
}
