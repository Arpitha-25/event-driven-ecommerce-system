package com.arpitha.inventory_service.idempotency;

import com.arpitha.inventory_service.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Idempotent-consumer support: remembers which event IDs each listener has handled.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessedEventStore {

    private final ProcessedEventRepository processedEventRepository;

    @Value("${app.idempotency.retention-days:7}")
    private int retentionDays;

    /**
     * Records the event as processed by this consumer.
     *
     * MANDATORY: the record must commit or roll back together with the consumer's own changes.
     * If the handler fails afterwards, the record is rolled back too and the retry runs normally.
     *
     * @return true the first time this consumer sees the event; false for a duplicate
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            log.warn("Event for {} has no eventId; it can't be de-duplicated by ID", consumer);
            return true;
        }
        return processedEventRepository.insertIfAbsent(consumer, eventId) == 1;
    }

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    @Transactional
    public void deleteOldRecords() {
        int deleted = processedEventRepository.deleteProcessedBefore(LocalDateTime.now().minusDays(retentionDays));
        if (deleted > 0) {
            log.info("Deleted {} processed-event records older than {} days", deleted, retentionDays);
        }
    }
}
