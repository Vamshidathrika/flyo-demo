package com.flyo.rescue.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistent Distributed Saga Execution.
 *
 * Tracks the end-to-end lifecycle and forward/compensating progression of
 * consequential multi-step operations (rebooking, payment holds, verification).
 */
@Entity
@Table(name = "saga_executions")
public class SagaExecution {

    // Status Constants
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_WAITING_FOR_EXTERNAL_RESULT = "WAITING_FOR_EXTERNAL_RESULT";
    public static final String STATUS_RETRY_PENDING = "RETRY_PENDING";
    public static final String STATUS_COMPENSATING = "COMPENSATING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_MANUAL_INTERVENTION_REQUIRED = "MANUAL_INTERVENTION_REQUIRED";

    // Step Constants for Rebooking Saga
    public static final String STEP_INITIATE = "INITIATE";
    public static final String STEP_AUTHORIZE_PAYMENT = "AUTHORIZE_PAYMENT";
    public static final String STEP_DISPATCH_REBOOK = "DISPATCH_REBOOK";
    public static final String STEP_RECONCILE_BOOKING = "RECONCILE_BOOKING";
    public static final String STEP_VERIFY_TICKET = "VERIFY_TICKET";
    public static final String STEP_NOTIFY_PASSENGER = "NOTIFY_PASSENGER";
    public static final String STEP_COMPLETED = "COMPLETED";

    @Id
    @Column(length = 64)
    private String sagaId;

    @Column(length = 64, nullable = false)
    private String caseId;

    @Column(length = 64, nullable = false)
    private String tenantId = "tenant_default";

    @Column(length = 64, nullable = false)
    private String sagaType; // "REBOOKING_SAGA"

    @Column(length = 64, nullable = false)
    private String currentStep = STEP_INITIATE;

    @Column(length = 32, nullable = false)
    private String status = STATUS_PENDING;

    private int attemptCount = 0;
    private int maxAttempts = 3;

    private LocalDateTime nextRetryAt;

    @Column(length = 64, nullable = false)
    private String correlationId;

    @Column(length = 128, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(length = 64)
    private String failureReasonCode;

    @Column(length = 64)
    private String ticketNumber;

    @Column(length = 32)
    private String ticketStatus = "UNVERIFIED";

    @Column(length = 32)
    private String notificationStatus = "PENDING";

    @Column(length = 32)
    private String crmSyncStatus = "PENDING";

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(columnDefinition = "TEXT")
    private String compensationPayload;

    @Column(length = 64)
    private String lockedBy;

    private LocalDateTime lockedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Version
    private Long version;

    public SagaExecution() {
    }

    public SagaExecution(String caseId, String sagaType, String payload, String correlationId, String idempotencyKey) {
        this.sagaId = "saga_" + UUID.randomUUID().toString();
        this.caseId = caseId;
        this.sagaType = sagaType;
        this.payload = payload;
        this.correlationId = correlationId != null ? correlationId : "corr_" + UUID.randomUUID().toString();
        this.idempotencyKey = idempotencyKey != null ? idempotencyKey : "idem_saga_" + caseId + "_" + System.currentTimeMillis();
        this.currentStep = STEP_INITIATE;
        this.status = STATUS_PENDING;
        this.attemptCount = 0;
        this.maxAttempts = 3;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    // Getters and Setters

    public String getSagaId() {
        return sagaId;
    }

    public void setSagaId(String sagaId) {
        this.sagaId = sagaId;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getSagaType() {
        return sagaType;
    }

    public void setSagaType(String sagaType) {
        this.sagaType = sagaType;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(String currentStep) {
        this.currentStep = currentStep;
        this.updatedAt = LocalDateTime.now();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getFailureReasonCode() {
        return failureReasonCode;
    }

    public void setFailureReasonCode(String failureReasonCode) {
        this.failureReasonCode = failureReasonCode;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getCompensationPayload() {
        return compensationPayload;
    }

    public void setCompensationPayload(String compensationPayload) {
        this.compensationPayload = compensationPayload;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    public void setTicketNumber(String ticketNumber) {
        this.ticketNumber = ticketNumber;
    }

    public String getTicketStatus() {
        return ticketStatus;
    }

    public void setTicketStatus(String ticketStatus) {
        this.ticketStatus = ticketStatus;
    }

    public String getNotificationStatus() {
        return notificationStatus;
    }

    public void setNotificationStatus(String notificationStatus) {
        this.notificationStatus = notificationStatus;
    }

    public String getCrmSyncStatus() {
        return crmSyncStatus;
    }

    public void setCrmSyncStatus(String crmSyncStatus) {
        this.crmSyncStatus = crmSyncStatus;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
