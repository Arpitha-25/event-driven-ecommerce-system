package com.arpitha.inventory_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.kafka.topics")
public class KafkaTopicProperties {
    private String orderCreated;
    private String orderCancelled;
    private String inventoryReserved;
    private String inventoryReservationFailed;
}
