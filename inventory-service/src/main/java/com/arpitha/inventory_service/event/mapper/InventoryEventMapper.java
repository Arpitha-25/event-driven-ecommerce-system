package com.arpitha.inventory_service.event.mapper;

import com.arpitha.common.event.EventMetadataFactory;
import com.arpitha.common.event.EventType;
import com.arpitha.inventory_service.event.model.InventoryReservationFailedEvent;
import com.arpitha.inventory_service.event.model.InventoryReservedEvent;
import com.arpitha.inventory_service.service.ReservationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryEventMapper {

    private final EventMetadataFactory eventMetadataFactory;

    public InventoryReservedEvent toReservedEvent(ReservationResult result) {
        InventoryReservedEvent event = new InventoryReservedEvent(
                result.orderId(),
                result.productId(),
                result.quantity()
        );
        return eventMetadataFactory.populateMetadata(event, EventType.INVENTORY_RESERVED);
    }

    public InventoryReservationFailedEvent toReservationFailedEvent(ReservationResult result) {
        InventoryReservationFailedEvent event = new InventoryReservationFailedEvent(
                result.orderId(),
                result.productId(),
                result.quantity(),
                result.reason()
        );
        return eventMetadataFactory.populateMetadata(event, EventType.INVENTORY_RESERVATION_FAILED);
    }
}
