package com.arpitha.product_service.config;

import com.arpitha.product_service.dto.ProductResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

/**
 * Redis cache for product reads (GET /api/v1/products/{id}).
 *
 * - Entries are JSON, expire after app.cache.product-ttl, and are keyed "product-service:products::<id>".
 * - The cache manager is transaction-aware: an eviction from updateProduct/deleteProduct happens
 *   only after the database transaction commits, so a concurrent read can't re-cache the old row
 *   in between. If the transaction rolls back, nothing is evicted.
 * - A Redis failure never fails the request: errors are logged and the read goes to PostgreSQL.
 * - Set spring.cache.type=none (SPRING_CACHE_TYPE=none) to turn caching off.
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    public static final String PRODUCTS = "products";

    @Bean
    public RedisCacheConfiguration productCacheConfiguration(ObjectMapper objectMapper,
                                                             @Value("${app.cache.product-ttl:10m}") Duration ttl) {
        // A copy of Spring Boot's ObjectMapper, so Instant/BigDecimal/enums round-trip exactly as in the API.
        Jackson2JsonRedisSerializer<ProductResponse> serializer =
                new Jackson2JsonRedisSerializer<>(objectMapper.copy(), ProductResponse.class);
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .prefixCacheNameWith("product-service:")
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(serializer));
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer transactionAwareCache() {
        return builder -> builder.transactionAware();
    }

    /**
     * The Redis client connects lazily on the first command. Connect at startup instead, so the
     * first shopper doesn't pay for it (seconds on a busy machine) and a Redis problem shows up
     * in the log straight away. Never stops startup: the service works without the cache.
     */
    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
    public ApplicationRunner redisWarmUp(RedisConnectionFactory connectionFactory) {
        return args -> {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                connection.ping();
                log.info("Product cache: connected to Redis");
            } catch (RuntimeException e) {
                log.warn("Product cache: could not connect to Redis at startup ({}); will retry on the next read, "
                        + "which uses PostgreSQL until Redis answers", rootCause(e));
            }
        };
    }

    /** Log cache errors (e.g. Redis unreachable), with their cause, and carry on without the cache. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                warn("read", cache, key, e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                warn("store", cache, key, e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                warn("evict", cache, key, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                warn("clear", cache, "*", e);
            }
        };
    }

    private static void warn(String action, Cache cache, Object key, RuntimeException e) {
        log.warn("Cache '{}' could not {} key '{}': {}", cache.getName(), action, key, rootCause(e));
    }

    private static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
