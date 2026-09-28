package com.arpitha.inventory_service.exception;

import com.arpitha.common.exception.BusinessException;

public class DuplicateInventoryException extends BusinessException {
    public DuplicateInventoryException(String message) {
        super(message, "DUPLICATE_INVENTORY");
    }
}
