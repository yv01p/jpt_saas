package org.jphototagger.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AlbumRequest(
        @NotBlank @Size(max = 255) String name
) {}
