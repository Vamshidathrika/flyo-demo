package com.flyo.rescue.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Versioned, provenance-tracked Airport Minimum Connection Time (MCT) regulatory rule entity.
 * Backed by authoritative database records, preventing hardcoded in-memory tables.
 */
@Entity
@Table(name = "airport_mct_rules")
public class AirportMctRule {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "airport_code", nullable = false, length = 8)
    private String airportCode;

    @Column(name = "transfer_type", nullable = false, length = 64)
    private String transferType;

    @Column(name = "published_mct_minutes", nullable = false)
    private int publishedMctMinutes;

    @Column(name = "flyo_policy_minutes", nullable = false)
    private int flyoPolicyMinutes;

    @Column(name = "source_authority", nullable = false, length = 128)
    private String sourceAuthority;

    @Column(name = "rule_version", nullable = false, length = 32)
    private String ruleVersion;

    @Column(name = "effective_date", nullable = false)
    private Instant effectiveDate;

    @Column(name = "expiry_date")
    private Instant expiryDate;

    @Column(name = "approval_status", nullable = false, length = 32)
    private String approvalStatus; // APPROVED, PENDING_REVIEW, SUPERSEDED

    @Column(name = "approved_by", nullable = false, length = 64)
    private String approvedBy;

    @Column(name = "last_verified_at", nullable = false)
    private Instant lastVerifiedAt;

    @Column(name = "provenance_reference", length = 256)
    private String provenanceReference;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AirportMctRule() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.lastVerifiedAt = Instant.now();
        this.tenantId = "tenant_default";
        this.approvalStatus = "APPROVED";
    }

    public AirportMctRule(String id, String airportCode, String transferType, int publishedMctMinutes,
                          int flyoPolicyMinutes, String sourceAuthority, String ruleVersion,
                          Instant effectiveDate, Instant expiryDate, String approvalStatus,
                          String approvedBy, Instant lastVerifiedAt, String provenanceReference,
                          String tenantId) {
        this.id = id;
        this.airportCode = airportCode;
        this.transferType = transferType;
        this.publishedMctMinutes = publishedMctMinutes;
        this.flyoPolicyMinutes = flyoPolicyMinutes;
        this.sourceAuthority = sourceAuthority;
        this.ruleVersion = ruleVersion;
        this.effectiveDate = effectiveDate;
        this.expiryDate = expiryDate;
        this.approvalStatus = approvalStatus;
        this.approvedBy = approvedBy;
        this.lastVerifiedAt = lastVerifiedAt != null ? lastVerifiedAt : Instant.now();
        this.provenanceReference = provenanceReference;
        this.tenantId = tenantId != null ? tenantId : "tenant_default";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAirportCode() { return airportCode; }
    public void setAirportCode(String airportCode) { this.airportCode = airportCode; }

    public String getTransferType() { return transferType; }
    public void setTransferType(String transferType) { this.transferType = transferType; }

    public int getPublishedMctMinutes() { return publishedMctMinutes; }
    public void setPublishedMctMinutes(int publishedMctMinutes) { this.publishedMctMinutes = publishedMctMinutes; }

    public int getFlyoPolicyMinutes() { return flyoPolicyMinutes; }
    public void setFlyoPolicyMinutes(int flyoPolicyMinutes) { this.flyoPolicyMinutes = flyoPolicyMinutes; }

    public String getSourceAuthority() { return sourceAuthority; }
    public void setSourceAuthority(String sourceAuthority) { this.sourceAuthority = sourceAuthority; }

    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }

    public Instant getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(Instant effectiveDate) { this.effectiveDate = effectiveDate; }

    public Instant getExpiryDate() { return expiryDate; }
    public void setExpiryDate(Instant expiryDate) { this.expiryDate = expiryDate; }

    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }

    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(Instant lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }

    public String getProvenanceReference() { return provenanceReference; }
    public void setProvenanceReference(String provenanceReference) { this.provenanceReference = provenanceReference; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
