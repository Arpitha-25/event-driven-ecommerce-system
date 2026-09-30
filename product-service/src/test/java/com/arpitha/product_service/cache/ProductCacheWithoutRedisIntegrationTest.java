package com.arpitha.product_service.cache;

import com.arpitha.common.util.TimeZoneNormalizer;
import com.arpitha.product_service.domain.enums.Category;
import com.arpitha.product_service.dto.CreateProductRequest;
import com.arpitha.product_service.dto.UpdateProductRequest;
import com.arpitha.product_service.event.publisher.ProductEventPublisher;
import com.arpitha.product_service.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis is down (nothing listens on the configured port): product reads and writes must still
 * work, straight from PostgreSQL, without long waits.
 */
@SpringBootTest(properties = {"spring.data.redis.host=localhost", "spring.data.redis.port=1"})
@Testcontainers(disabledWithoutDocker = true)
class ProductCacheWithoutRedisIntegrationTest {

    static {
        TimeZoneNormalizer.normalizeDefault();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private ProductService productService;

    @MockBean
    private ProductEventPublisher productEventPublisher;

    @Test
    void readsAndWrites_KeepWorking_WhenRedisIsUnreachable() {
        String unique = UUID.randomUUID().toString();
        CreateProductRequest create = new CreateProductRequest();
        create.setSku("NOREDIS-" + unique);
        create.setName("No Redis " + unique);
        create.setPrice(new BigDecimal("5.00"));
        create.setCategory(Category.BOOKS);
        UUID id = productService.createProduct(create).getId();

        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            assertEquals("No Redis " + unique, productService.getProductById(id).getName());
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 3000, "read " + i + " took " + millis + " ms; a Redis outage must not stall reads");
        }

        UpdateProductRequest update = new UpdateProductRequest();
        update.setName("Still works " + unique);
        productService.updateProduct(id, update);
        assertEquals("Still works " + unique, productService.getProductById(id).getName());
    }
}
