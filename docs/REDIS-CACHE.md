# Redis Cache for Product Reads

`GET /api/v1/products/{id}` is served from **Redis** after the first read, instead of querying PostgreSQL every time. The cached copy is removed when the product is updated or deleted.

---

## 1. How it works

```text
GET /products/{id}
   │
   ├─ in Redis? ── yes ──► return it                 (no database query)
   │
   └─ no ──► PostgreSQL ──► store in Redis (10 min) ──► return it

PUT /products/{id}, DELETE /products/{id}
   └─ change the row in PostgreSQL ──► commit ──► remove "product-service:products::{id}" from Redis
```

| Part | Where |
|------|-------|
| `@Cacheable(cacheNames = "products", key = "#id")` on `getProductById` | `ProductServiceImpl` |
| `@CacheEvict(cacheNames = "products", key = "#id")` on `updateProduct` and `deleteProduct` | `ProductServiceImpl` |
| Cache setup | `config/CacheConfig.java` |
| Settings | `spring.cache.type: redis`, `spring.data.redis.*`, `app.cache.product-ttl: 10m` |
| Redis container | `redis:7-alpine` in `docker-compose.yml`; product-service waits until it is healthy |

## 2. Design decisions

| Decision | Why |
|----------|-----|
| **Evict on write, instead of updating the cached copy** | The next read rebuilds the entry from the database, so the cache can never hold a value that wasn't committed. |
| **Transaction-aware cache manager** | The eviction runs **after the database commit**. Evicting before the commit would let a concurrent read put the old row straight back into Redis. If the transaction rolls back, nothing is evicted, which is correct because the data didn't change. |
| **10-minute TTL** | This bounds any staleness from edge cases, such as a row changed outside the service or a missed eviction during a Redis outage. |
| **Missing products aren't cached** (`disableCachingNullValues`) | A 404 is never stored, so a product created a moment later is found straight away. |
| **JSON values** using Spring Boot's own `ObjectMapper` | The cached copy is identical to the API response, including `BigDecimal`, enums and `Instant`, and it can be read with `redis-cli`. |
| **Keys are prefixed with the service name**: `product-service:products::<id>` | Other services could share the same Redis without collisions. |
| **A Redis failure never fails a request** (`LoggingCacheErrorHandler`, 500 ms timeouts) | The cache is an optimisation. Without Redis, reads and writes go straight to PostgreSQL and the errors are logged. The Redis health check is off, so an outage doesn't mark product-service as down. |
| **Only the by-id read is cached** | The paged product list (`GET /products`) isn't cached. Its results depend on page, size and sort, and every write would have to clear all of them. Spring's `Page` type also doesn't round-trip through JSON. |
| **Can be switched off**: `SPRING_CACHE_TYPE=none` (in compose: `PRODUCT_CACHE_TYPE=none`) | This is used for the latency comparison below, and it's a quick escape hatch. |
| **Redis is limited to 64 MB, least-recently-used keys dropped first** (`maxmemory 64mb`, `allkeys-lru`) | It's a cache, so when it's full the oldest entries should go, rather than writes failing. |

**Known limitation.** Evict-on-write leaves one narrow race:
1. A read loads the old row from PostgreSQL.
2. An update commits and evicts the key.
3. The slow read then stores the old row it had already loaded.

That stale copy lasts at most until the 10-minute TTL. For a product catalog this is acceptable. If it weren't, the options are a short delayed second eviction, or versioned keys.

---

## 3. Tests

### `ProductCacheIntegrationTest` (6 tests, real PostgreSQL and Redis via Testcontainers)
A spy on `ProductRepository` counts the database reads, so each test can tell where a read came from.

| Test | Proves |
|------|--------|
| `secondRead_IsServedFromRedis_WithoutTouchingTheDatabase` | Two reads, **one** `findById`. The cached copy equals the original, including price, enums and timestamps. |
| `cachedEntry_IsJsonInRedis_WithTheConfiguredExpiry` | The key `product-service:products::<id>` holds JSON with a TTL of 10 minutes or less. |
| `update_EvictsTheEntry_SoTheNextReadSeesTheChange` | After an update the key is gone, and the next read returns the new name and price. |
| `delete_EvictsTheEntry_SoTheNextReadSeesItDiscontinued` | After a delete, the next read shows `DISCONTINUED`. |
| `failedUpdate_RollsBack_AndLeavesTheCachedEntryInPlace` | An update that fails (duplicate name) evicts nothing, and the still-correct cached copy keeps being used with no database read. |
| `missingProduct_IsNotCached` | Two reads of a missing id make two database queries and store no key. |

### `ProductCacheWithoutRedisIntegrationTest` (1 test)
Redis points at a port where nothing is listening. Create, read three times and update all still work, each read takes well under 3 seconds, and the log names the cause of each handled error, for example `Cache 'products' could not read key '…': ConnectException: Connection refused`.

### `ProductCacheSystemTest` (3 tests, the real images through the API gateway)
It runs in the shared system-test environment: PostgreSQL, Kafka, Redis and all five service images.

| Test | Proves |
|------|--------|
| `read_IsCachedInRedis_AndAnUpdateThroughTheGateway_IsVisibleOnTheNextRead` | A read through the gateway stores the key in the real Redis, with a TTL of 10 minutes or less. An admin's `PUT` removes it. The next read shows the new price and caches it again. |
| `delete_IsVisibleOnTheNextRead` | After a `DELETE`, the next read through the gateway shows `DISCONTINUED`. |
| `whenRedisStopsResponding_ReadsStillSucceedFromPostgres` | `CLIENT PAUSE` makes Redis accept connections but answer nothing for 5 seconds. The read still succeeds, from PostgreSQL, in under 4 seconds. The log shows `RedisCommandTimeoutException` being handled. |

### Checking the tests catch a missing cache
With caching switched off (`-Dspring.cache.type=none`), **3 of the 6 cache integration tests fail**: the Redis hit, the stored entry, and the entry surviving a rollback. Those are exactly the three that prove caching happens. The other three check correctness (fresh data after a change, no cached 404s), so they rightly pass either way.

---

## 4. Measured latency

`scripts/measure-product-cache.ps1` reads one product 2,000 times with the cache on, then 2,000 times with it off, after 300 warm-up requests each time. It restarts product-service between runs with `PRODUCT_CACHE_TYPE=redis` or `none`, and afterwards puts the cache back on. It calls product-service directly on :8082, so the gateway doesn't blur the comparison.

It also checks each mode is real before measuring: with the cache on, the product must be in Redis after the warm-up; with it off, it must not be.

| | median | p95 | p99 | mean | requests/s |
|---|---|---|---|---|---|
| Cache off (PostgreSQL) | 4.94 ms | 7.33 ms | 10.22 ms | 5.35 ms | 187 |
| **Cache on (Redis)** | **3.78 ms** | **5.63 ms** | **8.08 ms** | **4.02 ms** | **249** |

**About 1.3× faster** (median and p95), with about 33% more reads per second on a single connection. Measured on a laptop with 8 GB of RAM running the whole stack in Docker.

**Why not more:** the database work the cache removes is a primary-key lookup on a small, local table, which PostgreSQL answers in about a millisecond. Most of each request is HTTP and Spring overhead, which the cache doesn't touch. The saving grows when the database is remote, busy, or the query is expensive, and every cache hit is also a query PostgreSQL no longer has to serve.

---

## 5. Problems found by running it

1. **The first read after startup took 15 seconds and timed out at the gateway (504).** The Redis client connects lazily, on the first command. That first connection happened while ten containers were starting on a busy machine, and it failed after about 15 s. It was handled correctly (the read fell back to PostgreSQL), but far too slowly for the first shopper. The fix: product-service now **connects to Redis at startup** (an `ApplicationRunner` that pings Redis and never blocks startup). After a restart on a quiet machine, the first read takes under a second.
2. **Cache errors were logged without their cause.** Spring's `LoggingCacheErrorHandler` hides the exception by default, which made problem 1 hard to diagnose. A small custom handler now logs the root cause in one line (`ConnectException: Connection refused`, `RedisCommandTimeoutException: …`).
3. **Kafka's health check used a large Java heap.** `KAFKA_HEAP_OPTS` (added with the memory limits) was also inherited by the health check's own JVM, which runs every 10 seconds inside Kafka's container. The check now gets `-Xmx64m`, and Kafka's idle CPU dropped from 92% to 4%.
4. **The latency script called the wrong command.** Its function was named `Measure`, but in PowerShell `measure` is a built-in alias for `Measure-Object`, which takes priority, so the script printed empty results. It's now `Measure-Latency`.

## 6. Results

| Check | Result |
|-------|--------|
| product-service tests (unit + Testcontainers) | **23/23** (16 existing + 7 cache) |
| System tests (all images, PostgreSQL, Kafka, Redis) | **15/15**: `ProductCacheSystemTest` 3/3, `GatewaySystemTest` 5/5, `OrderFlowSystemTest` 7/7; 0 ERROR lines in any service log |
| End-to-end scripts on the running stack | gateway + auth 21/21 · saga 19/19 · reliable consumers 17/17 · Kafka outage 9/9 |
| Latency | median 4.94 → 3.78 ms, p95 7.33 → 5.63 ms (about 1.3×) |

## 7. Memory limits (added at the same time)

Every container in `docker-compose.yml` now has a memory limit:
- 640 MB for each Java service; each JVM sizes its heap to 75% of that.
- 768 MB for Kafka (heap up to 512 MB).
- 512 MB for PostgreSQL, 384 MB for Kafka UI, 128 MB for Redis.

Without limits, every JVM sized itself as if it had the whole Docker VM, so on an 8 GB laptop the stack pushed Windows into heavy paging. Measured use after startup: about 190 to 340 MB per Java service and about 370 MB for Kafka, all well inside their limits.
