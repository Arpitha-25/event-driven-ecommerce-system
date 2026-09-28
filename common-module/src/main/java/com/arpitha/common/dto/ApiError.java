package com.arpitha.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiError {
    private boolean success;
    private String errorCode;
    private String message;
    private String correlationId;
    
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
