package com.arpitha.product_service.exception;

import com.arpitha.common.exception.BusinessException;

public class InvalidProductStateException extends BusinessException {
    public InvalidProductStateException(String message) {
        super(message, "INVALID_PRODUCT_STATE");
    }
}
