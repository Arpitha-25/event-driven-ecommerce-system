package com.arpitha.identity_service.controller;

import com.arpitha.common.dto.ApiResponse;
import com.arpitha.identity_service.dto.LoginRequest;
import com.arpitha.identity_service.dto.RegisterRequest;
import com.arpitha.identity_service.dto.TokenResponse;
import com.arpitha.identity_service.dto.UserResponse;
import com.arpitha.identity_service.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth Controller", description = "Accounts and access tokens")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account", description = "New accounts get the USER role")
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.<UserResponse>builder()
                .success(true)
                .message("Account created")
                .data(authService.register(request))
                .build();
    }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Returns a signed JWT access token")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.<TokenResponse>builder()
                .success(true)
                .message("Logged in")
                .data(authService.login(request))
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "The current user", description = "Requires 'Authorization: Bearer <token>'")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.<UserResponse>builder()
                .success(true)
                .message("Current user")
                .data(authService.getUser(UUID.fromString(jwt.getSubject())))
                .build();
    }
}
