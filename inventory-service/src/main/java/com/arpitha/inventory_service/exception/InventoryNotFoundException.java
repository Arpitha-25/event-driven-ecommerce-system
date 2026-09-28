package com.arpitha.inventory_service.exception;

import com.arpitha.common.exception.ResourceNotFoundException;

public class InventoryNotFoundException extends ResourceNotFoundException {
    public InventoryNotFoundException(String message) {
        super(message);
    }
}
