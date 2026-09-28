package com.arpitha.inventory_service.event.publisher;

import com.arpitha.inventory_service.event.model.InventoryReservationFailedEvent;
import com.arpitha.inventory_service.event.model.InventoryReservedEvent;

public interface InventoryEventPublisher {

    void publishInventoryReservedEvent(InventoryReservedEvent event);

    void publishInventoryReservationFailedEvent(InventoryReservationFailedEvent event);
}
