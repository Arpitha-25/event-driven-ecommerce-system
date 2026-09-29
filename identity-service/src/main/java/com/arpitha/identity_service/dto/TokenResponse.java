package com.arpitha.identity_service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An access token to send as 'Authorization: Bearer <accessToken>'")
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn) {
}
