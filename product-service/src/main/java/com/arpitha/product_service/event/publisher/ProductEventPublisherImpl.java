package com.arpitha.product_service.event.publisher;

import com.arpitha.product_service.config.KafkaTopicProperties;
import com.arpitha.product_service.event.model.ProductCreatedEvent;
import com.arpitha.product_service.event.model.ProductDeletedEvent;
import com.arpitha.product_service.event.model.ProductUpdatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ProductEventPublisherImpl implements ProductEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicProperties topicProperties;

    public ProductEventPublisherImpl(KafkaTemplate<String, Object> kafkaTemplate,
                                     KafkaTopicProperties topicProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicProperties = topicProperties;
    }

    @Override
    public void publish(ProductCreatedEvent event) {
        log.info("Publishing ProductCreatedEvent for Product ID: {}, Correlation ID: {}, Event ID: {}", 
                 event.getProductId(), event.getCorrelationId(), event.getEventId());
        kafkaTemplate.send(topicProperties.getProductCreated(), event.getProductId().toString(), event);
    }

    @Override
    public void publish(ProductUpdatedEvent event) {
        log.info("Publishing ProductUpdatedEvent for Product ID: {}, Correlation ID: {}, Event ID: {}", 
                 event.getProductId(), event.getCorrelationId(), event.getEventId());
        kafkaTemplate.send(topicProperties.getProductUpdated(), event.getProductId().toString(), event);
    }

    @Override
    public void publish(ProductDeletedEvent event) {
        log.info("Publishing ProductDeletedEvent for Product ID: {}, Correlation ID: {}, Event ID: {}", 
                 event.getProductId(), event.getCorrelationId(), event.getEventId());
        kafkaTemplate.send(topicProperties.getProductDeleted(), event.getProductId().toString(), event);
    }
}
