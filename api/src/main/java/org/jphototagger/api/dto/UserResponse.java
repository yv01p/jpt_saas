package org.jphototagger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jphototagger.api.entity.User;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        @JsonProperty("show_gps") boolean showGps,
        @JsonProperty("quota_bytes") long quotaBytes,
        @JsonProperty("used_bytes") long usedBytes
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(), user.getEmail(), user.isShowGps(),
                user.getQuotaBytes(), user.getUsedBytes());
    }
}
