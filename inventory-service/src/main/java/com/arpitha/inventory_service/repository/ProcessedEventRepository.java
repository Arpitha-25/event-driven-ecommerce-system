package com.arpitha.inventory_service.repository;

import com.arpitha.inventory_service.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    /**
     * Returns 1 if the event was recorded now, 0 if it was already recorded. If two copies
     * arrive at the same moment, the second insert waits on the unique index and then returns 0.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_events (consumer, event_id, processed_at)
            VALUES (:consumer, :eventId, now())
            ON CONFLICT (consumer, event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("consumer") String consumer, @Param("eventId") String eventId);

    @Modifying
    @Query("delete from ProcessedEvent p where p.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") LocalDateTime cutoff);
}
