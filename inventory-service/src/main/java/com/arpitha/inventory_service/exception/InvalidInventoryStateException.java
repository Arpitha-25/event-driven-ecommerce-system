package com.arpitha.inventory_service.exception;

import com.arpitha.common.exception.BusinessException;

public class InvalidInventoryStateException extends BusinessException {
    public InvalidInventoryStateException(String message) {
        super(message, "INVALID_INVENTORY_STATE");
    }
}
