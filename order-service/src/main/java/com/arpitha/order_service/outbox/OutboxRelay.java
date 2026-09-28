package com.arpitha.order_service.outbox;

import com.arpitha.order_service.config.OutboxProperties;
import com.arpitha.order_service.entity.OutboxEvent;
import com.arpitha.order_service.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox events to Kafka, oldest first, and marks each one as published.
 *
 * Delivery is at-least-once: if the service stops after Kafka accepted an event but before
 * publishedAt was committed, that event is sent again on the next run. The consumers are
 * idempotent, so this is safe.
 */
@Slf4j
@Component
public class OutboxRelay {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       @Qualifier("outboxKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                       TransactionTemplate transactionTemplate,
                       OutboxProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        // Keep going while batches come back full, so a backlog drains without waiting.
        Boolean moreWaiting;
        do {
            moreWaiting = transactionTemplate.execute(status -> publishBatch());
        } while (Boolean.TRUE.equals(moreWaiting));
    }

    /**
     * Publishes one locked batch. Stops at the first failure so events for an order
     * are never published out of order; the failed event is retried on the next run.
     *
     * @return true if the batch was full and fully published, meaning more may be waiting
     */
    boolean publishBatch() {
        List<OutboxEvent> events = outboxEventRepository.lockNextUnpublished(properties.getBatchSize());

        for (OutboxEvent event : events) {
            event.setAttempts(event.getAttempts() + 1);
            try {
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                        .get(properties.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordFailure(event, e);
                return false;
            } catch (Exception e) {
                recordFailure(event, e);
                return false;
            }
            event.setPublishedAt(LocalDateTime.now());
            event.setLastError(null);
            log.info("Published outbox event {} ({}) for {} {} to {}",
                    event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId(), event.getTopic());
        }

        return events.size() == properties.getBatchSize();
    }

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    public void deleteOldPublishedEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetentionDays());
        Integer deleted = transactionTemplate.execute(status -> outboxEventRepository.deletePublishedBefore(cutoff));
        if (deleted != null && deleted > 0) {
            log.info("Deleted {} published outbox events older than {} days", deleted, properties.getRetentionDays());
        }
    }

    private void recordFailure(OutboxEvent event, Exception e) {
        // Kafka wraps the useful message (e.g. "Topic ... not present in metadata") a few levels down.
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String detail = cause.getMessage() != null
                ? cause.getMessage()
                : "no acknowledgement from Kafka within " + properties.getSendTimeoutMs() + " ms";
        String message = cause.getClass().getSimpleName() + ": " + detail;
        event.setLastError(message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message);
        log.warn("Could not publish outbox event {} (attempt {}), will retry: {}",
                event.getId(), event.getAttempts(), message);
    }
}
