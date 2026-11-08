package com.flyo.rescue.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Versioned Transactional Outbox Event.
 *
 * Implements durable event staging alongside local relational database transactions,
 * supporting distributed at-least-once delivery, tenant isolation, and idempotent consumption.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CLAIMED = "CLAIMED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_DEAD_LETTER = "DEAD_LETTER";

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 64, nullable = false)
    private String eventType;

    @Column(nullable = false)
    private int eventVersion = 1;

    @Column(length = 64, nullable = false)
    private String aggregateType;

    @Column(length = 64, nullable = false)
    private String aggregateId;

    @Column(length = 64)
    private String tenantId = "default";

    @Column(length = 64)
    private String correlationId;

    @Column(length = 64)
    private String causationId;

    @Column(length = 128, unique = true)
    private String idempotencyKey;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(length = 32, nullable = false)
    private String status = STATUS_PENDING;

    private int retryCount = 0;
    private int maxRetries = 5;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime availableAt = LocalDateTime.now();

    private LocalDateTime publishedAt;

    @Column(length = 64)
    private String lockedBy;

    private LocalDateTime lockedAt;

    @Column(length = 64)
    private String lastErrorCode;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    public OutboxEvent() {
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        this.id = UUID.randomUUID().toString();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = STATUS_PENDING;
        this.eventVersion = 1;
        this.retryCount = 0;
        this.maxRetries = 5;
        this.createdAt = LocalDateTime.now();
        this.availableAt = this.createdAt;
        this.tenantId = "default";
        this.correlationId = "corr_" + UUID.randomUUID().toString();
        this.idempotencyKey = "idem_" + this.id;
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload,
                       String correlationId, String causationId, String idempotencyKey) {
        this(aggregateType, aggregateId, eventType, payload);
        if (correlationId != null) this.correlationId = correlationId;
        if (causationId != null) this.causationId = causationId;
        if (idempotencyKey != null) this.idempotencyKey = idempotencyKey;
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    public void setEventVersion(int eventVersion) {
        this.eventVersion = eventVersion;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getCausationId() {
        return causationId;
    }

    public void setCausationId(String causationId) {
        this.causationId = causationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(LocalDateTime availableAt) {
        this.availableAt = availableAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
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

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
