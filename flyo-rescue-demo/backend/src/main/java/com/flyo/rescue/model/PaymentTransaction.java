package com.flyo.rescue.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    public static final String STATUS_NOT_REQUESTED = "NOT_REQUESTED";
    public static final String STATUS_AUTHORIZATION_PENDING = "AUTHORIZATION_PENDING";
    public static final String STATUS_AUTHORIZED = "AUTHORIZED";
    public static final String STATUS_CAPTURE_PENDING = "CAPTURE_PENDING";
    public static final String STATUS_CAPTURED = "CAPTURED";
    public static final String STATUS_VOID_PENDING = "VOID_PENDING";
    public static final String STATUS_VOIDED = "VOIDED";
    public static final String STATUS_SETTLEMENT_PENDING = "SETTLEMENT_PENDING";
    public static final String STATUS_SETTLED = "SETTLED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_MANUAL_REVIEW_REQUIRED = "MANUAL_REVIEW_REQUIRED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    public static final String TYPE_AUTH = "AUTH";
    public static final String TYPE_CAPTURE = "CAPTURE";
    public static final String TYPE_VOID = "VOID";
    public static final String TYPE_REFUND = "REFUND";

    @Id
    private String id;
    private String caseId;
    private String tenantId = "tenant_default";
    private String virtualCardId;
    private int amount;
    private String currency = "INR"; // "INR", "USD", "EUR"
    private String vendor; // e.g. "StarSky Airlines / Air India"
    private String status; // "SETTLED", "PENDING", "FAILED", etc.
    private String referenceId;
    private LocalDateTime timestamp;

    @Version
    private Long version;

    @Column(length = 128)
    private String idempotencyKey;

    @Column(length = 32)
    private String transactionType = TYPE_CAPTURE;

    private Boolean reconciled = false;

    private LocalDateTime reconciledAt;

    @Column(length = 256)
    private String failureReason;

    @Column(columnDefinition = "TEXT")
    private String auditMetadata;

    public PaymentTransaction() {
    }

    public PaymentTransaction(String id, String caseId, String virtualCardId, int amount,
                              String currency, String vendor, String status,
                              String referenceId, LocalDateTime timestamp) {
        this.id = id;
        this.caseId = caseId;
        this.virtualCardId = virtualCardId;
        this.amount = amount;
        this.currency = currency;
        this.vendor = vendor;
        this.status = status;
        this.referenceId = referenceId;
        this.timestamp = timestamp;
        this.tenantId = "tenant_default";
        this.transactionType = TYPE_CAPTURE;
        this.reconciled = false;
    }

    public PaymentTransaction(String id, String caseId, String tenantId, String virtualCardId, int amount,
                              String currency, String vendor, String status,
                              String referenceId, LocalDateTime timestamp) {
        this(id, caseId, virtualCardId, amount, currency, vendor, status, referenceId, timestamp);
        if (tenantId != null && !tenantId.isBlank()) {
            this.tenantId = tenantId;
        }
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
        return tenantId != null ? tenantId : "tenant_default";
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getVirtualCardId() {
        return virtualCardId;
    }

    public void setVirtualCardId(String virtualCardId) {
        this.virtualCardId = virtualCardId;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public Boolean getReconciled() {
        return reconciled;
    }

    public void setReconciled(Boolean reconciled) {
        this.reconciled = reconciled;
    }

    public LocalDateTime getReconciledAt() {
        return reconciledAt;
    }

    public void setReconciledAt(LocalDateTime reconciledAt) {
        this.reconciledAt = reconciledAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getAuditMetadata() {
        return auditMetadata;
    }

    public void setAuditMetadata(String auditMetadata) {
        this.auditMetadata = auditMetadata;
    }
}
