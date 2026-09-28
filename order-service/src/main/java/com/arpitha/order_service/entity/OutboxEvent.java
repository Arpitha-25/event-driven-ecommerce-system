package com.arpitha.order_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * An event waiting to be published to Kafka. It is written in the same database
 * transaction as the order change it describes, so either both are saved or neither is.
 * OutboxRelay publishes it later and sets publishedAt.
 */
@Entity
@Table(name = "outbox_events",
        indexes = @Index(name = "idx_outbox_events_unpublished", columnList = "publishedAt, id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String messageKey;

    /** The event, already serialized to JSON. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Null until the relay has published the event. */
    private LocalDateTime publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 1000)
    private String lastError;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
