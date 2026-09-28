package com.arpitha.product_service.exception;

import com.arpitha.common.exception.BusinessException;

public class DuplicateProductException extends BusinessException {
    public DuplicateProductException(String message) {
        super(message, "DUPLICATE_PRODUCT");
    }
}
