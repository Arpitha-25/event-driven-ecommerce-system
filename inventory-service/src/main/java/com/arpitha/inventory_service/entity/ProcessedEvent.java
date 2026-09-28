package com.arpitha.inventory_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Records that a consumer has handled an event. It is written in the same transaction as
 * the consumer's own changes, so an event counts as processed only if those changes committed.
 */
@Entity
@Table(name = "processed_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_processed_events_consumer_event",
                columnNames = {"consumer", "event_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Which listener handled it, e.g. "inventory-service:order.created.v1". */
    @Column(name = "consumer", nullable = false)
    private String consumer;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;
}
