package com.arpitha.systemtests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The product cache in the real system: the product-service image, its Redis and PostgreSQL,
 * called through the API gateway. Runs against the shared {@link SystemEnvironment}.
 */
@Testcontainers(disabledWithoutDocker = true)
class ProductCacheSystemTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final String gateway = SystemEnvironment.gatewayUrl();

    @AfterAll
    static void saveServiceLogs() throws IOException {
        SystemEnvironment.saveLogs();
    }

    @Test
    void read_IsCachedInRedis_AndAnUpdateThroughTheGateway_IsVisibleOnTheNextRead() throws Exception {
        String admin = adminToken();
        String id = createProduct(admin, "19.99");
        String key = "product-service:products::" + id;

        assertEquals(0, new BigDecimal("19.99").compareTo(price(read(id))));
        assertEquals("1", redis("EXISTS", key), "the first read stored the product in Redis");
        long ttl = Long.parseLong(redis("TTL", key));
        assertTrue(ttl > 0 && ttl <= 600, "expires within 10 minutes, ttl=" + ttl);

        expect(200, send("PUT", "/api/v1/products/" + id, admin, Map.of("price", 24.5)));

        assertEquals("0", redis("EXISTS", key), "the update removed the cached copy");
        assertEquals(0, new BigDecimal("24.50").compareTo(price(read(id))), "the next read shows the new price");
        assertEquals("1", redis("EXISTS", key), "and caches it again");
    }

    @Test
    void delete_IsVisibleOnTheNextRead() throws Exception {
        String admin = adminToken();
        String id = createProduct(admin, "5.00");
        assertEquals("ACTIVE", read(id).get("status").asText());

        expect(200, send("DELETE", "/api/v1/products/" + id, admin, null));

        assertEquals("DISCONTINUED", read(id).get("status").asText());
    }

    @Test
    void whenRedisStopsResponding_ReadsStillSucceedFromPostgres() throws Exception {
        String id = createProduct(adminToken(), "7.00");
        read(id);

        // Redis accepts connections but answers nothing for 5 seconds.
        redis("CLIENT", "PAUSE", "5000", "ALL");
        long start = System.nanoTime();
        JsonNode product = read(id);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(id, product.get("id").asText());
        assertTrue(millis < 4000, "read took " + millis + " ms; it must fall back to PostgreSQL, not wait for Redis");
    }

    // ---------------------------------------------------------------------------------------

    private String adminToken() throws Exception {
        return expect(200, send("POST", "/api/v1/auth/login", null,
                Map.of("email", SystemEnvironment.ADMIN_EMAIL, "password", SystemEnvironment.ADMIN_PASSWORD)))
                .get("data").get("accessToken").asText();
    }

    private String createProduct(String admin, String price) throws Exception {
        String sku = "CACHE-SYS-" + UUID.randomUUID();
        return expect(201, send("POST", "/api/v1/products", admin, Map.of(
                "sku", sku, "name", "Cache system test " + sku, "price", new BigDecimal(price), "category", "BOOKS")))
                .get("data").get("id").asText();
    }

    /** Anonymous read through the gateway, as any shopper would. */
    private JsonNode read(String id) throws Exception {
        return expect(200, send("GET", "/api/v1/products/" + id, null, null)).get("data");
    }

    private static BigDecimal price(JsonNode product) {
        return product.get("price").decimalValue();
    }

    private static String redis(String... command) throws Exception {
        String[] cli = new String[command.length + 1];
        cli[0] = "redis-cli";
        System.arraycopy(command, 0, cli, 1, command.length);
        ExecResult result = SystemEnvironment.REDIS.execInContainer(cli);
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout().trim();
    }

    private HttpResponse<String> send(String method, String path, String token, Map<String, ?> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gateway + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
