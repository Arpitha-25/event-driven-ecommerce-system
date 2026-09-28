package com.arpitha.order_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {
    /** Pause between relay runs, in milliseconds. */
    private long pollIntervalMs = 1000;
    /** Most events published per relay run. */
    private int batchSize = 50;
    /** How long to wait for Kafka to acknowledge one event, in milliseconds. */
    private long sendTimeoutMs = 10000;
    /** Published events older than this many days are deleted. */
    private int retentionDays = 7;
}
