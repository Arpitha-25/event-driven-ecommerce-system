package com.arpitha.order_service.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class OrderSummaryResponse {
    private Long id;
    private String status;
    private LocalDateTime createdAt;
}
