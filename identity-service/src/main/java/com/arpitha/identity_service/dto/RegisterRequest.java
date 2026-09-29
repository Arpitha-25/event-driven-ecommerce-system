package com.arpitha.identity_service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Request payload for creating an account")
public class RegisterRequest {

    @Schema(example = "ana@example.com")
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    @Size(max = 255, message = "Email must be at most 255 characters")
    private String email;

    @Schema(example = "correct-horse-battery")
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;

    @Schema(example = "Ana Silva")
    @Size(max = 255, message = "Full name must be at most 255 characters")
    private String fullName;
}
