package com.arpitha.identity_service.exception;

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

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ApiError> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException ex) {
        return error(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        return new ResponseEntity<>(validationHelper.extractValidationErrors(ex), HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        ApiError error = ApiError.builder()
                .success(false)
                .errorCode(code)
                .message(message)
                .correlationId(CorrelationIdContext.getCorrelationId())
                .build();
        return new ResponseEntity<>(error, status);
    }
}
