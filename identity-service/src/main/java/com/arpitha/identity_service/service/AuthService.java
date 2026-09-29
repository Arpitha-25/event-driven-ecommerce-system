package com.arpitha.identity_service.service;

import com.arpitha.identity_service.dto.LoginRequest;
import com.arpitha.identity_service.dto.RegisterRequest;
import com.arpitha.identity_service.dto.TokenResponse;
import com.arpitha.identity_service.dto.UserResponse;

import java.util.UUID;

public interface AuthService {

    /** Creates a USER account. The email is case-insensitive and must be unique. */
    UserResponse register(RegisterRequest request);

    /** Checks the credentials and returns a signed access token. */
    TokenResponse login(LoginRequest request);

    UserResponse getUser(UUID id);
}
