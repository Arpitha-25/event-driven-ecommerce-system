package com.arpitha.systemtests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole order -> inventory -> order-status flow, tested as a black box.
 *
 * Runs against the shared {@link SystemEnvironment} (everything in Docker, using the images
 * docker-compose runs) and calls order-service and inventory-service directly through their
 * REST APIs, then looks inside the databases and Kafka to check the outbox, the processed
 * events and the correlation ID. The gateway and security are covered by GatewaySystemTest.
 */
@Testcontainers(disabledWithoutDocker = true)
class OrderFlowSystemTest {

    private static final Duration SAGA_TIMEOUT = Duration.ofSeconds(30);

    // The shared environment (SystemEnvironment) starts on first use, once for all test classes.
    private static final PostgreSQLContainer<?> POSTGRES = SystemEnvironment.POSTGRES;
    private static final KafkaContainer KAFKA = SystemEnvironment.KAFKA;
    private static final GenericContainer<?> ORDER_SERVICE = SystemEnvironment.ORDER_SERVICE;
    private static final GenericContainer<?> INVENTORY_SERVICE = SystemEnvironment.INVENTORY_SERVICE;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    /** Keep each service's log in target/ so a failure can be investigated. */
    @AfterAll
    static void saveServiceLogs() throws IOException {
        SystemEnvironment.saveLogs();
    }

    // ---------------------------------------------------------------------------------------

    @Test
    void orderWithinStock_IsConfirmed_AndStockIsReserved() throws Exception {
        UUID productId = createStock(10);

        JsonNode order = placeOrder(productId, 3, null);
        assertEquals("CREATED", order.get("status").asText(), "the saga starts asynchronously");

        assertEquals("CONFIRMED", awaitSettledStatus(order.get("id").asLong()).get("status").asText());
        assertStock(productId, 3, 7, "ACTIVE");
    }

    @Test
    void orderAboveStock_IsRejectedWithReason_AndStockIsUntouched() throws Exception {
        UUID productId = createStock(5);

        JsonNode order = awaitSettledStatus(placeOrder(productId, 8, null).get("id").asLong());

        assertEquals("REJECTED", order.get("status").asText());
        assertTrue(order.get("statusReason").asText().contains("requested 8, available 5"), order.toString());
        assertStock(productId, 0, 5, "ACTIVE");
    }

    @Test
    void orderForProductWithoutInventory_IsRejected() throws Exception {
        JsonNode order = awaitSettledStatus(placeOrder(UUID.randomUUID(), 1, null).get("id").asLong());

        assertEquals("REJECTED", order.get("status").asText());
        assertTrue(order.get("statusReason").asText().startsWith("No inventory found"), order.toString());
    }

    @Test
    void cancellingConfirmedOrder_ReleasesItsStock() throws Exception {
        UUID productId = createStock(10);
        long orderId = placeOrder(productId, 4, null).get("id").asLong();
        assertEquals("CONFIRMED", awaitSettledStatus(orderId).get("status").asText());
        assertStock(productId, 4, 6, "ACTIVE");

        HttpResponse<String> response = send(HttpRequest.newBuilder(orderUrl("/" + orderId)).DELETE().build());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("CANCELLED", getOrder(orderId).get("status").asText());

        awaitStock(productId, 0, 10);
    }

    @Test
    void reservingLastUnits_MarksOutOfStock_AndNextOrderIsRejected() throws Exception {
        UUID productId = createStock(2);

        assertEquals("CONFIRMED", awaitSettledStatus(placeOrder(productId, 2, null).get("id").asLong()).get("status").asText());
        assertStock(productId, 2, 0, "OUT_OF_STOCK");

        assertEquals("REJECTED", awaitSettledStatus(placeOrder(productId, 1, null).get("id").asLong()).get("status").asText());
    }

    @Test
    void eventsFlowThroughOutbox_AreDeduplicatedByConsumers_AndKeepTheCorrelationId() throws Exception {
        UUID productId = createStock(10);
        String correlationId = "system-test-" + UUID.randomUUID();

        long orderId = placeOrder(productId, 1, correlationId).get("id").asLong();
        assertEquals("CONFIRMED", awaitSettledStatus(orderId).get("status").asText());

        // order-service: the event went through the outbox and was published.
        Map<String, Object> outbox = queryOne("orderdb",
                "SELECT event_type, published_at IS NOT NULL AS published, payload FROM outbox_events " +
                "WHERE aggregate_id = ? AND event_type = 'ORDER_CREATED'", String.valueOf(orderId));
        assertEquals(true, outbox.get("published"));
        JsonNode created = json.readTree((String) outbox.get("payload"));
        assertEquals(correlationId, created.get("correlationId").asText(), "request header -> outbox event");

        // inventory-service recorded the event as processed, exactly once.
        Map<String, Object> processed = queryOne("ecommerce_inventory_db",
                "SELECT count(*) AS n FROM processed_events WHERE consumer = 'inventory-service:order.created.v1' AND event_id = ?",
                created.get("eventId").asText());
        assertEquals(1L, processed.get("n"));

        // inventory's reply carries the same correlation ID, and order-service processed it once.
        JsonNode reply = awaitKafkaMessage("inventory.reserved.v1", "\"orderId\":" + orderId + ",");
        assertEquals(correlationId, reply.get("correlationId").asText(), "outbox event -> inventory reply");
        Map<String, Object> replyProcessed = queryOne("orderdb",
                "SELECT count(*) AS n FROM processed_events WHERE consumer = 'order-service:inventory.reserved.v1' AND event_id = ?",
                reply.get("eventId").asText());
        assertEquals(1L, replyProcessed.get("n"));
    }

    @Test
    void invalidOrder_IsRejectedByValidation() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(orderUrl(""))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"productName\":\"No product id\",\"quantity\":1,\"price\":10}"))
                .build());
        assertEquals(400, response.statusCode(), response.body());
    }

    // ---------------------------------------------------------------------------------------

    private URI orderUrl(String path) {
        return URI.create("http://" + ORDER_SERVICE.getHost() + ":" + ORDER_SERVICE.getMappedPort(8080) + "/api/v1/orders" + path);
    }

    private URI inventoryUrl(String path) {
        return URI.create("http://" + INVENTORY_SERVICE.getHost() + ":" + INVENTORY_SERVICE.getMappedPort(8083) + "/api/v1/inventory" + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postJson(URI uri, Map<String, Object> body, String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (correlationId != null) {
            request.header("X-Correlation-ID", correlationId);
        }
        HttpResponse<String> response = send(request.build());
        assertEquals(201, response.statusCode(), response.body());
        return json.readTree(response.body()).get("data");
    }

    private JsonNode getData(URI uri) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri).GET().build());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).get("data");
    }

    private UUID createStock(int quantity) throws Exception {
        UUID productId = UUID.randomUUID();
        postJson(inventoryUrl(""), Map.of("productId", productId, "sku", "SYS-" + productId, "totalQuantity", quantity), null);
        return productId;
    }

    private JsonNode placeOrder(UUID productId, int quantity, String correlationId) throws Exception {
        return postJson(orderUrl(""),
                Map.of("productId", productId, "productName", "System test product", "quantity", quantity, "price", 100),
                correlationId);
    }

    private JsonNode getOrder(long orderId) throws Exception {
        return getData(orderUrl("/" + orderId));
    }

    private JsonNode getStock(UUID productId) throws Exception {
        return getData(inventoryUrl("/product/" + productId));
    }

    /** Waits for the saga: the order leaves CREATED once inventory has answered. */
    private JsonNode awaitSettledStatus(long orderId) throws Exception {
        long deadline = System.currentTimeMillis() + SAGA_TIMEOUT.toMillis();
        JsonNode order;
        do {
            order = getOrder(orderId);
            if (!"CREATED".equals(order.get("status").asText())) {
                return order;
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        fail("Order " + orderId + " was still CREATED after " + SAGA_TIMEOUT.toSeconds() + " s: " + order);
        return order;
    }

    private void assertStock(UUID productId, int reserved, int available, String status) throws Exception {
        JsonNode stock = getStock(productId);
        assertEquals(reserved, stock.get("reservedQuantity").asInt(), "reserved: " + stock);
        assertEquals(available, stock.get("availableQuantity").asInt(), "available: " + stock);
        assertEquals(status, stock.get("status").asText(), "status: " + stock);
    }

    private void awaitStock(UUID productId, int reserved, int available) throws Exception {
        long deadline = System.currentTimeMillis() + SAGA_TIMEOUT.toMillis();
        JsonNode stock;
        do {
            stock = getStock(productId);
            if (stock.get("reservedQuantity").asInt() == reserved && stock.get("availableQuantity").asInt() == available) {
                return;
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        fail("Stock never reached reserved=" + reserved + ", available=" + available + ": " + stock);
    }

    private Map<String, Object> queryOne(String database, String sql, String parameter) throws Exception {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), "no row for " + parameter);
                Map<String, Object> row = new java.util.HashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                return row;
            }
        }
    }

    private JsonNode awaitKafkaMessage(String topic, String mustContain) throws Exception {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "system-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + SAGA_TIMEOUT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains(mustContain)) {
                        return json.readTree(record.value());
                    }
                }
            }
        }
        fail("No message containing " + mustContain + " on " + topic);
        return null;
    }
}
