package com.arpitha.product_service.event.mapper;

import com.arpitha.common.event.EventMetadataFactory;
import com.arpitha.common.event.EventType;
import com.arpitha.product_service.entity.Product;
import com.arpitha.product_service.event.model.ProductCreatedEvent;
import com.arpitha.product_service.event.model.ProductDeletedEvent;
import com.arpitha.product_service.event.model.ProductUpdatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductEventMapper {

    private final EventMetadataFactory eventMetadataFactory;

    public ProductCreatedEvent toCreatedEvent(Product product, String correlationId) {
        ProductCreatedEvent event = new ProductCreatedEvent(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getPrice(),
                product.getStatus().name()
        );
        eventMetadataFactory.populateMetadata(event, EventType.PRODUCT_CREATED);
        event.setCorrelationId(correlationId); // Overwrite if passed explicitly
        return event;
    }

    public ProductUpdatedEvent toUpdatedEvent(Product product, String correlationId) {
        ProductUpdatedEvent event = new ProductUpdatedEvent(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getPrice(),
                product.getStatus().name()
        );
        eventMetadataFactory.populateMetadata(event, EventType.PRODUCT_UPDATED);
        event.setCorrelationId(correlationId);
        return event;
    }

    public ProductDeletedEvent toDeletedEvent(Product product, String correlationId) {
        ProductDeletedEvent event = new ProductDeletedEvent(
                product.getId(),
                product.getSku()
        );
        eventMetadataFactory.populateMetadata(event, EventType.PRODUCT_DELETED);
        event.setCorrelationId(correlationId);
        return event;
    }
}
