package com.arpitha.order_service.idempotency;

import com.arpitha.common.util.TimeZoneNormalizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs ProcessedEventStore against real PostgreSQL, because its guarantees come from the
 * database: the unique index and INSERT ... ON CONFLICT DO NOTHING under concurrency.
 * Skipped automatically when Docker isn't available.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProcessedEventStore.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // each thread needs its own real transaction
class ProcessedEventStoreIntegrationTest {

    static {
        // Same as the services' main(): PostgreSQL rejects legacy zone IDs such as "Asia/Calcutta".
        TimeZoneNormalizer.normalizeDefault();
    }

    private static final String CONSUMER = "order-service:inventory.reserved.v1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private ProcessedEventStore store;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private boolean markInNewTransaction(String consumer, String eventId) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                .execute(status -> store.markProcessed(consumer, eventId)));
    }

    @Test
    void sameEventMarkedConcurrently_OnlyOneCallerIsFirst() throws Exception {
        String eventId = UUID.randomUUID().toString();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return markInNewTransaction(CONSUMER, eventId);
                }));
            }
            start.countDown();

            int firsts = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    firsts++;
                }
            }
            assertEquals(1, firsts, "exactly one of the concurrent copies is treated as new");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, rowsFor(eventId));
    }

    @Test
    void rolledBackRecord_DoesNotCountAsProcessed() {
        String eventId = UUID.randomUUID().toString();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertTrue(store.markProcessed(CONSUMER, eventId));
            status.setRollbackOnly(); // the handler's work failed
        });

        assertEquals(0, rowsFor(eventId), "rolled back with the failed work");
        assertTrue(markInNewTransaction(CONSUMER, eventId), "the retry is processed normally");
        assertFalse(markInNewTransaction(CONSUMER, eventId), "a later copy is a duplicate");
    }

    @Test
    void sameEventForDifferentConsumers_IsTrackedSeparately() {
        String eventId = UUID.randomUUID().toString();

        assertTrue(markInNewTransaction(CONSUMER, eventId));
        assertTrue(markInNewTransaction("order-service:inventory.reservation-failed.v1", eventId));
        assertFalse(markInNewTransaction(CONSUMER, eventId));
    }

    @Test
    void markProcessed_RefusesToRunOutsideATransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> store.markProcessed(CONSUMER, UUID.randomUUID().toString()),
                "MANDATORY: must share the handler's transaction");
    }

    private int rowsFor(String eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
    }
}
