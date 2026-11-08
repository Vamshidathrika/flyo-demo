package com.flyo.rescue.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Historical audit log for individual saga execution steps.
 */
@Entity
@Table(name = "saga_step_logs")
public class SagaStepLog {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String sagaId;

    @Column(length = 64, nullable = false)
    private String tenantId = "tenant_default";

    @Column(length = 64, nullable = false)
    private String stepName;

    @Column(length = 64, nullable = false)
    private String action; // EXECUTE, RECONCILE, COMPENSATE

    @Column(length = 32, nullable = false)
    private String status; // STARTED, SUCCEEDED, FAILED, SKIPPED

    @Column(columnDefinition = "TEXT")
    private String inputPayload;

    @Column(columnDefinition = "TEXT")
    private String outputPayload;

    @Column(columnDefinition = "TEXT")
    private String errorDetails;

    private Long durationMs;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public SagaStepLog() {
    }

    public SagaStepLog(String sagaId, String stepName, String action, String status,
                       String inputPayload, String outputPayload, String errorDetails, Long durationMs) {
        this(sagaId, "tenant_default", stepName, action, status, inputPayload, outputPayload, errorDetails, durationMs);
    }

    public SagaStepLog(String sagaId, String tenantId, String stepName, String action, String status,
                       String inputPayload, String outputPayload, String errorDetails, Long durationMs) {
        this.id = "slog_" + UUID.randomUUID().toString();
        this.sagaId = sagaId;
        this.tenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId : "tenant_default";
        this.stepName = stepName;
        this.action = action;
        this.status = status;
        this.inputPayload = inputPayload;
        this.outputPayload = outputPayload;
        this.errorDetails = errorDetails;
        this.durationMs = durationMs;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSagaId() {
        return sagaId;
    }

    public void setSagaId(String sagaId) {
        this.sagaId = sagaId;
    }

    public String getTenantId() {
        return tenantId != null ? tenantId : "tenant_default";
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInputPayload() {
        return inputPayload;
    }

    public void setInputPayload(String inputPayload) {
        this.inputPayload = inputPayload;
    }

    public String getOutputPayload() {
        return outputPayload;
    }

    public void setOutputPayload(String outputPayload) {
        this.outputPayload = outputPayload;
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    public void setErrorDetails(String errorDetails) {
        this.errorDetails = errorDetails;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
