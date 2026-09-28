package com.arpitha.inventory_service.integration;

import com.arpitha.inventory_service.entity.Inventory;
import com.arpitha.inventory_service.enums.InventoryStatus;
import com.arpitha.inventory_service.event.consumer.OrderEventHandler;
import com.arpitha.inventory_service.event.incoming.OrderCreatedMessage;
import com.arpitha.inventory_service.event.publisher.InventoryEventPublisher;
import com.arpitha.inventory_service.repository.InventoryRepository;
import com.arpitha.inventory_service.service.ReservationResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import com.arpitha.common.util.TimeZoneNormalizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Runs the consumers against real PostgreSQL and Kafka (the same images as docker-compose),
 * to prove the cases that unit tests with mocks can't: real concurrency on real locks and
 * unique indexes, and a real Kafka redelivery after a lost reply.
 *
 * Skipped automatically when Docker isn't available.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReliableConsumerIntegrationTest {

    static {
        // Same as the services' main(): PostgreSQL rejects legacy zone IDs such as "Asia/Calcutta".
        TimeZoneNormalizer.normalizeDefault();
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.jpa.show-sql", () -> "false");
    }

    @Autowired
    private OrderEventHandler orderEventHandler;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private InventoryEventPublisher inventoryEventPublisher;

    // ---------------------------------------------------------------------------------------
    // 1. The same event delivered several times at the same moment
    // ---------------------------------------------------------------------------------------

    @Test
    void sameEventArrivingConcurrently_IsProcessedOnce() throws Exception {
        UUID productId = createStock(10);
        long orderId = newOrderId();
        OrderCreatedMessage event = new OrderCreatedMessage(UUID.randomUUID().toString(), "it", orderId, productId, 3);

        List<Outcome<ReservationResult>> outcomes = runConcurrently(8, i -> () -> orderEventHandler.handleOrderCreated(event));

        for (Outcome<ReservationResult> outcome : outcomes) {
            assertNull(outcome.error(), () -> "no copy should fail: " + outcome.error());
            assertEquals(ReservationResult.Outcome.RESERVED, outcome.value().outcome());
        }
        assertStock(productId, 3, 7);
        assertEquals(1, reservationsFor(orderId), "one reservation row");
        assertEquals(1, processedRowsFor(event.eventId()), "eventId recorded once");
    }

    // ---------------------------------------------------------------------------------------
    // 2. The same order arriving at the same moment as *different* events (different eventIds),
    //    e.g. a manual resend. The eventId check can't catch this; the unique order_id must.
    // ---------------------------------------------------------------------------------------

    @Test
    void sameOrderAsDifferentEventsConcurrently_ReservesOnce_AndRetriesReplay() throws Exception {
        UUID productId = createStock(10);
        long orderId = newOrderId();
        List<OrderCreatedMessage> events = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            events.add(new OrderCreatedMessage(UUID.randomUUID().toString(), "it", orderId, productId, 3));
        }

        List<Outcome<ReservationResult>> outcomes = runConcurrently(8, i -> () -> orderEventHandler.handleOrderCreated(events.get(i)));

        long succeeded = outcomes.stream().filter(o -> o.error() == null).count();
        assertTrue(succeeded >= 1, "at least one copy succeeds");
        for (Outcome<ReservationResult> outcome : outcomes) {
            if (outcome.error() != null) {
                // The losers hit the unique order_id and roll back completely; Kafka would retry them.
                assertInstanceOf(DataIntegrityViolationException.class, outcome.error(), outcome.error().toString());
            } else {
                assertEquals(ReservationResult.Outcome.RESERVED, outcome.value().outcome());
            }
        }
        assertStock(productId, 3, 7);
        assertEquals(1, reservationsFor(orderId));

        // A rolled-back attempt leaves no processed-event record, so its retry runs in full...
        for (int i = 0; i < events.size(); i++) {
            if (outcomes.get(i).error() != null) {
                assertEquals(0, processedRowsFor(events.get(i).eventId()));
            }
        }
        // ...and the retry replays the stored reservation without touching stock.
        for (int i = 0; i < events.size(); i++) {
            if (outcomes.get(i).error() != null) {
                assertEquals(ReservationResult.Outcome.RESERVED, orderEventHandler.handleOrderCreated(events.get(i)).outcome());
            }
        }
        assertStock(productId, 3, 7);
    }

    // ---------------------------------------------------------------------------------------
    // 2b. A duplicate eventId must never reserve stock, even for an order it hasn't seen.
    //     (Regression: this used to reserve when an eventId was reused for a new order.)
    // ---------------------------------------------------------------------------------------

    @Test
    void reusedEventIdForUnseenOrder_NeverReservesStock() {
        UUID productId = createStock(10);
        String eventId = UUID.randomUUID().toString();
        long firstOrder = newOrderId();
        long otherOrder = newOrderId();

        orderEventHandler.handleOrderCreated(new OrderCreatedMessage(eventId, "it", firstOrder, productId, 2));
        ReservationResult result = orderEventHandler.handleOrderCreated(new OrderCreatedMessage(eventId, "it", otherOrder, productId, 2));

        assertEquals(ReservationResult.Outcome.IGNORED, result.outcome());
        assertStock(productId, 2, 8);
        assertEquals(0, reservationsFor(otherOrder));
    }

    // ---------------------------------------------------------------------------------------
    // 3. Many different orders racing for the last units
    // ---------------------------------------------------------------------------------------

    @Test
    void manyOrdersForLastUnitsConcurrently_NeverOversell() throws Exception {
        UUID productId = createStock(5);

        List<Outcome<ReservationResult>> outcomes = runConcurrently(20, i -> () -> orderEventHandler.handleOrderCreated(
                new OrderCreatedMessage(UUID.randomUUID().toString(), "it", newOrderId(), productId, 1)));

        outcomes.forEach(o -> assertNull(o.error(), () -> "unexpected failure: " + o.error()));
        long reserved = outcomes.stream().filter(o -> o.value().outcome() == ReservationResult.Outcome.RESERVED).count();
        long failed = outcomes.stream().filter(o -> o.value().outcome() == ReservationResult.Outcome.FAILED).count();

        assertEquals(5, reserved, "exactly the 5 available units are reserved");
        assertEquals(15, failed, "every other order is rejected");
        assertStock(productId, 5, 0);
        assertEquals(InventoryStatus.OUT_OF_STOCK, inventoryRepository.findByProductId(productId).orElseThrow().getStatus());
    }

    // ---------------------------------------------------------------------------------------
    // 4. The reply is lost after the reservation committed; Kafka redelivers the same event
    // ---------------------------------------------------------------------------------------

    @Test
    void replyLostAfterCommit_IsResentOnKafkaRedelivery_WithoutReservingTwice() throws Exception {
        UUID productId = createStock(10);
        long orderId = newOrderId();
        String eventId = UUID.randomUUID().toString();

        // First send of the reply fails (as if Kafka dropped it); later sends go through for real.
        doThrow(new KafkaException("Simulated: reply lost after the reservation committed"))
                .doCallRealMethod()
                .when(inventoryEventPublisher).publishInventoryReservedEvent(any());

        String payload = String.format(
                "{\"eventId\":\"%s\",\"correlationId\":\"it\",\"orderId\":%d,\"productId\":\"%s\",\"quantity\":2}",
                eventId, orderId, productId);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>("order.created.v1", String.valueOf(orderId), payload)).get(10, TimeUnit.SECONDS);
        }

        // order-service would be waiting for this reply; it must arrive exactly once.
        List<String> replies = readReplies("inventory.reserved.v1", orderId, Duration.ofSeconds(45), Duration.ofSeconds(5));
        assertEquals(1, replies.size(), () -> "expected exactly one reply, got " + replies);

        verify(inventoryEventPublisher, times(2)).publishInventoryReservedEvent(any());
        assertStock(productId, 2, 8);
        assertEquals(1, reservationsFor(orderId), "reserved once, not once per delivery");
        assertEquals(1, processedRowsFor(eventId), "the redelivery was recognised as a duplicate");
    }

    // ---------------------------------------------------------------------------------------

    private record Outcome<T>(T value, Throwable error) {
    }

    /** Starts all tasks at the same instant and collects each result or exception. */
    private <T> List<Outcome<T>> runConcurrently(int count, IntFunction<Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<T> callable = task.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return callable.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(new Outcome<>(future.get(60, TimeUnit.SECONDS), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome<>(null, e.getCause()));
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Reads every reply for the order until none has arrived for {@code quiet} (after the first). */
    private List<String> readReplies(String topic, long orderId, Duration timeout, Duration quiet) {
        List<String> replies = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            long quietUntil = Long.MAX_VALUE;
            while (System.currentTimeMillis() < Math.min(deadline, quietUntil)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains("\"orderId\":" + orderId + ",")) {
                        replies.add(record.value());
                        quietUntil = System.currentTimeMillis() + quiet.toMillis();
                    }
                }
            }
        }
        return replies;
    }

    private UUID createStock(int quantity) {
        UUID productId = UUID.randomUUID();
        inventoryRepository.save(Inventory.builder()
                .productId(productId)
                .sku("IT-" + productId)
                .totalQuantity(quantity)
                .reservedQuantity(0)
                .availableQuantity(quantity)
                .status(InventoryStatus.ACTIVE)
                .build());
        return productId;
    }

    private void assertStock(UUID productId, int reserved, int available) {
        Inventory inventory = inventoryRepository.findByProductId(productId).orElseThrow();
        assertEquals(reserved, inventory.getReservedQuantity(), "reserved quantity");
        assertEquals(available, inventory.getAvailableQuantity(), "available quantity");
    }

    private int reservationsFor(long orderId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM stock_reservations WHERE order_id = ?", Integer.class, orderId);
    }

    private int processedRowsFor(String eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
    }

    private static long newOrderId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
    }
}
