package com.flyo.rescue.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "discrepancy_records")
public class DiscrepancyRecord {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String caseId;

    @Column(length = 64, nullable = false)
    private String tenantId = "tenant_default";

    @Column(length = 64, nullable = false)
    private String discrepancyType;

    @Column(length = 64, nullable = false)
    private String localState;

    @Column(length = 64, nullable = false)
    private String remoteState;

    @Column(columnDefinition = "TEXT")
    private String details;

    private boolean resolved = false;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime resolvedAt;

    public DiscrepancyRecord() {
    }

    public DiscrepancyRecord(String id, String caseId, String tenantId, String discrepancyType,
                             String localState, String remoteState, String details) {
        this.id = id;
        this.caseId = caseId;
        this.tenantId = tenantId != null ? tenantId : "tenant_default";
        this.discrepancyType = discrepancyType;
        this.localState = localState;
        this.remoteState = remoteState;
        this.details = details;
        this.resolved = false;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getDiscrepancyType() {
        return discrepancyType;
    }

    public void setDiscrepancyType(String discrepancyType) {
        this.discrepancyType = discrepancyType;
    }

    public String getLocalState() {
        return localState;
    }

    public void setLocalState(String localState) {
        this.localState = localState;
    }

    public String getRemoteState() {
        return remoteState;
    }

    public void setRemoteState(String remoteState) {
        this.remoteState = remoteState;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
