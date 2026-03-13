package org.jphototagger.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

public class ShareResponse {

    private UUID id;
    private String resourceType;
    private UUID resourceId;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String token; // Only present in create response
    private Instant expiresAt;
    private String permissions;
    private boolean includeGps;
    private Instant createdAt;

    public ShareResponse() {}

    public ShareResponse(UUID id, String resourceType, UUID resourceId, String token,
                         Instant expiresAt, String permissions, boolean includeGps, Instant createdAt) {
        this.id = id;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.token = token;
        this.expiresAt = expiresAt;
        this.permissions = permissions;
        this.includeGps = includeGps;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public UUID getResourceId() { return resourceId; }
    public void setResourceId(UUID resourceId) { this.resourceId = resourceId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public String getPermissions() { return permissions; }
    public void setPermissions(String permissions) { this.permissions = permissions; }

    public boolean isIncludeGps() { return includeGps; }
    public void setIncludeGps(boolean includeGps) { this.includeGps = includeGps; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
