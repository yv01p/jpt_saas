package org.jphototagger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateUserRequest(
        @JsonProperty("show_gps") Boolean showGps
) {}
