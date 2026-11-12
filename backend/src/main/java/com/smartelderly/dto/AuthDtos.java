package com.smartelderly.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request/response bodies for /api/auth. */
public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email address") String email,
            @NotBlank(message = "Password is required") String password) {}

    public record RegisterRequest(
            @NotBlank(message = "Full name is required") @Size(min = 2, max = 80, message = "Name must be 2-80 characters") String fullName,
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email address") @Size(max = 120) String email,
            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "Password must contain letters and numbers")
            String password) {}

    public record UserDto(String id, String email, String fullName, String role) {}

    public record AuthResponse(String token, Instant expiresAt, UserDto user) {}

    public record RegistrationStatus(boolean registrationOpen, boolean firstUser) {}
}
