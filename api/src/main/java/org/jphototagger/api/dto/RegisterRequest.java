package org.jphototagger.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Email @NotBlank String email,
        @Size(min = 12, message = "Password must be at least 12 characters") @NotBlank String password
) {}
