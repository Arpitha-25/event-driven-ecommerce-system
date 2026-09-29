package com.arpitha.identity_service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request payload for logging in")
public class LoginRequest {

    @Schema(example = "ana@example.com")
    @NotBlank(message = "Email is required")
    private String email;

    @Schema(example = "correct-horse-battery")
    @NotBlank(message = "Password is required")
    private String password;
}
