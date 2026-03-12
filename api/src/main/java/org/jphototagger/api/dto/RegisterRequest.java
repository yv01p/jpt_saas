package org.jphototagger.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Email @NotBlank String email,
        @Size(min = 12, max = 128, message = "Password must be between 12 and 128 characters") @NotBlank String password
) {}
