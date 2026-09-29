package com.arpitha.identity_service.dto;

import com.arpitha.identity_service.entity.Role;
import com.arpitha.identity_service.entity.UserAccount;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, String fullName, Role role, Instant createdAt) {

    public static UserResponse from(UserAccount user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.getCreatedAt());
    }
}
