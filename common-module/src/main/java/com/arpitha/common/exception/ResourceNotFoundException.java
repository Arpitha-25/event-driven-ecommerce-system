package com.arpitha.common.exception;

import com.arpitha.common.constant.ErrorCodes;

public abstract class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(message, ErrorCodes.RESOURCE_NOT_FOUND);
    }
}
