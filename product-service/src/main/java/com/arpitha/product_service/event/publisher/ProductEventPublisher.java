package com.arpitha.product_service.event.publisher;

import com.arpitha.product_service.event.model.ProductCreatedEvent;
import com.arpitha.product_service.event.model.ProductDeletedEvent;
import com.arpitha.product_service.event.model.ProductUpdatedEvent;

public interface ProductEventPublisher {
    
    void publish(ProductCreatedEvent event);
    
    void publish(ProductUpdatedEvent event);
    
    void publish(ProductDeletedEvent event);
}
