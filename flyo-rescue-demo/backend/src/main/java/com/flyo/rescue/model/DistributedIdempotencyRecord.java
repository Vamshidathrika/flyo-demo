package com.flyo.rescue.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "distributed_idempotency_records")
public class DistributedIdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", length = 190)
    private String idempotencyKey;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "default";

    @Column(name = "scope", length = 64, nullable = false)
    private String scope;

    @Column(name = "status", length = 32, nullable = false)
    private String status = "IN_PROGRESS";

    @Column(name = "locked_by", length = 64)
    private String lockedBy;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "response_payload", columnDefinition = "TEXT")
    private String responsePayload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    public DistributedIdempotencyRecord() {
    }

    public DistributedIdempotencyRecord(String idempotencyKey, String tenantId, String scope, String status, String lockedBy, LocalDateTime expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.tenantId = tenantId != null ? tenantId : "default";
        this.scope = scope;
        this.status = status != null ? status : "IN_PROGRESS";
        this.lockedBy = lockedBy;
        this.lockedAt = LocalDateTime.now();
        this.createdAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public LocalDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(LocalDateTime lockedAt) {
        this.lockedAt = lockedAt;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public void setResponsePayload(String responsePayload) {
        this.responsePayload = responsePayload;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
