package com.arpitha.product_service.exception;

import com.arpitha.common.dto.ApiError;
import com.arpitha.common.filter.CorrelationIdContext;
import com.arpitha.common.util.ValidationHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ValidationHelper validationHelper;

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> handleProductNotFoundException(ProductNotFoundException ex) {
        ApiError error = ApiError.builder()
                .success(false)
                .errorCode("PRODUCT_NOT_FOUND")
                .message(ex.getMessage())
                .correlationId(CorrelationIdContext.getCorrelationId())
                .build();
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(DuplicateProductException.class)
    public ResponseEntity<ApiError> handleDuplicateProductException(DuplicateProductException ex) {
        ApiError error = ApiError.builder()
                .success(false)
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(CorrelationIdContext.getCorrelationId())
                .build();
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = validationHelper.extractValidationErrors(ex);
        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(InvalidProductStateException.class)
    public ResponseEntity<ApiError> handleInvalidProductStateException(InvalidProductStateException ex) {
        ApiError error = ApiError.builder()
                .success(false)
                .errorCode(ex.getErrorCode())
                .message(ex.getMessage())
                .correlationId(CorrelationIdContext.getCorrelationId())
                .build();
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }
}
