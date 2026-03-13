package org.jphototagger.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public class CreateShareRequest {

    @NotBlank
    @Pattern(regexp = "photo|album", message = "resourceType must be 'photo' or 'album'")
    private String resourceType;

    @NotNull
    private UUID resourceId;

    private boolean includeGps = false;

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public UUID getResourceId() { return resourceId; }
    public void setResourceId(UUID resourceId) { this.resourceId = resourceId; }

    public boolean isIncludeGps() { return includeGps; }
    public void setIncludeGps(boolean includeGps) { this.includeGps = includeGps; }
}
