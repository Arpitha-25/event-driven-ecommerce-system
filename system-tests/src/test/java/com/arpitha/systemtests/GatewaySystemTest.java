package com.arpitha.systemtests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Clients' view of the system: everything goes through the API gateway on one port, with
 * tokens issued by identity-service. Runs against the shared {@link SystemEnvironment}.
 */
@Testcontainers(disabledWithoutDocker = true)
class GatewaySystemTest {

    private static final Duration SAGA_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final String gateway = SystemEnvironment.gatewayUrl();

    @AfterAll
    static void saveServiceLogs() throws IOException {
        SystemEnvironment.saveLogs();
    }

    // ---------------------------------------------------------------------------------------

    @Test
    void completeUserJourney_ThroughTheGateway() throws Exception {
        // An admin sets up a product and its stock.
        String admin = login(SystemEnvironment.ADMIN_EMAIL, SystemEnvironment.ADMIN_PASSWORD);
        String sku = "GW-" + UUID.randomUUID();
        JsonNode product = expect(201, send("POST", "/api/v1/products", admin, Map.of(
                "sku", sku, "name", "Gateway test product " + sku, "description", "created through the gateway",
                "price", 49.99, "category", "ELECTRONICS"))).get("data");
        UUID productId = UUID.fromString(product.get("id").asText());
        expect(201, send("POST", "/api/v1/inventory", admin, Map.of(
                "productId", productId, "sku", sku, "totalQuantity", 5)));

        // A customer registers, logs in and orders.
        String email = "customer-" + UUID.randomUUID() + "@example.com";
        JsonNode account = expect(201, send("POST", "/api/v1/auth/register", null, Map.of(
                "email", email, "password", "customer-password-1", "fullName", "Gateway Customer"))).get("data");
        assertEquals("USER", account.get("role").asText());
        String customer = login(email, "customer-password-1");

        JsonNode me = expect(200, send("GET", "/api/v1/auth/me", customer, null)).get("data");
        assertEquals(email, me.get("email").asText());

        String correlationId = "gateway-journey-" + UUID.randomUUID();
        HttpResponse<String> placed = send("POST", "/api/v1/orders", customer, Map.of(
                "productId", productId, "productName", "Gateway test product", "quantity", 2, "price", 49.99), correlationId);
        long orderId = expect(201, placed).get("data").get("id").asLong();
        assertEquals(correlationId, placed.headers().firstValue("X-Correlation-ID").orElse(null));

        // The saga runs behind the gateway: order-service -> Kafka -> inventory-service -> Kafka -> order-service.
        assertEquals("CONFIRMED", awaitSettledStatus(orderId, customer).get("status").asText());
        JsonNode stock = expect(200, send("GET", "/api/v1/inventory/product/" + productId, customer, null)).get("data");
        assertEquals(2, stock.get("reservedQuantity").asInt());
        assertEquals(3, stock.get("availableQuantity").asInt());

        // The gateway forwarded the correlation ID all the way into the order's outbox event.
        String payload = outboxPayload(orderId);
        assertEquals(correlationId, json.readTree(payload).get("correlationId").asText());
    }

    @Test
    void anonymousClients_CanBrowseProducts_ButNothingElse() throws Exception {
        expect(200, send("GET", "/api/v1/products", null, null));

        assertUnauthorized(send("GET", "/api/v1/orders/1", null, null));
        assertUnauthorized(send("POST", "/api/v1/orders", null, Map.of("productId", UUID.randomUUID(),
                "productName", "x", "quantity", 1, "price", 1)));
        assertUnauthorized(send("GET", "/api/v1/inventory", null, null));
        assertUnauthorized(send("GET", "/api/v1/auth/me", null, null));
    }

    @Test
    void customers_CannotChangeProductsOrStock() throws Exception {
        String email = "customer-" + UUID.randomUUID() + "@example.com";
        expect(201, send("POST", "/api/v1/auth/register", null, Map.of("email", email, "password", "customer-password-1")));
        String customer = login(email, "customer-password-1");

        String sku = "NOPE-" + UUID.randomUUID();
        String name = "Not allowed " + sku;
        assertForbidden(send("POST", "/api/v1/products", customer, Map.of(
                "sku", sku, "name", name, "price", 1, "category", "BOOKS")));
        assertForbidden(send("POST", "/api/v1/inventory", customer, Map.of(
                "productId", UUID.randomUUID(), "sku", sku, "totalQuantity", 1)));

        // Double-check nothing was created behind the gateway (the list returns summaries: id, name, ...).
        JsonNode products = expect(200, send("GET", "/api/v1/products?size=1000", null, null)).get("data").get("content");
        assertTrue(products.size() > 0, "the list is not empty, so the check below means something");
        for (JsonNode p : products) {
            assertNotEquals(name, p.get("name").asText());
        }
    }

    @Test
    void forgedAndTamperedTokens_AreRejected() throws Exception {
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("attacker").generate();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("attacker").build(),
                new JWTClaimsSet.Builder()
                        .issuer("ecommerce-identity-service")
                        .subject(UUID.randomUUID().toString())
                        .claim("roles", List.of("ADMIN"))
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                        .build());
        forged.sign(new RSASSASigner(attackerKey));
        assertUnauthorized(send("GET", "/api/v1/orders/1", forged.serialize(), null));

        String email = "customer-" + UUID.randomUUID() + "@example.com";
        expect(201, send("POST", "/api/v1/auth/register", null, Map.of("email", email, "password", "customer-password-1")));
        String genuine = login(email, "customer-password-1");
        String[] parts = genuine.split("\\.");
        String elevatedClaims = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                        .replace("\"USER\"", "\"ADMIN\"").getBytes());
        assertUnauthorized(send("POST", "/api/v1/products", parts[0] + "." + elevatedClaims + "." + parts[2],
                Map.of("sku", "TAMPER-" + UUID.randomUUID(), "name", "x", "price", 1, "category", "BOOKS")));
    }

    @Test
    void wrongPassword_IsRejectedByIdentityService_ThroughTheGateway() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/auth/login", null,
                Map.of("email", SystemEnvironment.ADMIN_EMAIL, "password", "not-the-password"));
        assertEquals(401, response.statusCode());
        assertEquals("INVALID_CREDENTIALS", json.readTree(response.body()).get("errorCode").asText());
    }

    // ---------------------------------------------------------------------------------------

    private String login(String email, String password) throws Exception {
        return expect(200, send("POST", "/api/v1/auth/login", null, Map.of("email", email, "password", password)))
                .get("data").get("accessToken").asText();
    }

    private HttpResponse<String> send(String method, String path, String token, Map<String, ?> body) throws Exception {
        return send(method, path, token, body, null);
    }

    private HttpResponse<String> send(String method, String path, String token, Map<String, ?> body, String correlationId)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gateway + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            request.header("X-Correlation-ID", correlationId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private void assertUnauthorized(HttpResponse<String> response) throws Exception {
        assertEquals(401, response.statusCode(), response.body());
        assertEquals("UNAUTHORIZED", json.readTree(response.body()).get("errorCode").asText());
    }

    private void assertForbidden(HttpResponse<String> response) throws Exception {
        assertEquals(403, response.statusCode(), response.body());
        assertEquals("FORBIDDEN", json.readTree(response.body()).get("errorCode").asText());
    }

    private JsonNode awaitSettledStatus(long orderId, String token) throws Exception {
        long deadline = System.currentTimeMillis() + SAGA_TIMEOUT.toMillis();
        JsonNode order;
        do {
            order = expect(200, send("GET", "/api/v1/orders/" + orderId, token, null)).get("data");
            if (!"CREATED".equals(order.get("status").asText())) {
                return order;
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        fail("Order " + orderId + " was still CREATED after " + SAGA_TIMEOUT.toSeconds() + " s: " + order);
        return order;
    }

    private String outboxPayload(long orderId) throws Exception {
        String url = "jdbc:postgresql://" + SystemEnvironment.POSTGRES.getHost() + ":"
                + SystemEnvironment.POSTGRES.getMappedPort(5432) + "/orderdb";
        try (Connection connection = DriverManager.getConnection(url, "postgres", "postgres");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT payload FROM outbox_events WHERE aggregate_id = ? AND event_type = 'ORDER_CREATED'")) {
            statement.setString(1, String.valueOf(orderId));
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), "outbox row for order " + orderId);
                return rs.getString(1);
            }
        }
    }
}
