package com.arpitha.product_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.kafka.topics")
public class KafkaTopicProperties {
    private String productCreated;
    private String productUpdated;
    private String productDeleted;
}
