package org.jphototagger.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record KeywordRequest(
        @NotBlank @Size(max = 255) String name,
        UUID parentId
) {}
