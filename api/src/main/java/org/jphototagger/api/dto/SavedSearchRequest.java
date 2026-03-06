package org.jphototagger.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SavedSearchRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 10000) String queryJson
) {}
