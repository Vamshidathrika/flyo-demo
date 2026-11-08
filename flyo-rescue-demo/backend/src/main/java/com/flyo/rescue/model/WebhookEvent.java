package com.flyo.rescue.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    @Column(length = 128)
    private String eventId;

    @Column(length = 64, nullable = false)
    private String tenantId = "tenant_default";

    @Column(length = 64, nullable = false)
    private String eventType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(length = 256)
    private String signature;

    @Column(length = 32, nullable = false)
    private String status = "PROCESSED";

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private LocalDateTime processedAt = LocalDateTime.now();
    private LocalDateTime createdAt = LocalDateTime.now();

    public WebhookEvent() {
    }

    public WebhookEvent(String eventId, String tenantId, String eventType, String payload, String signature, String status) {
        this.eventId = eventId;
        this.tenantId = tenantId != null ? tenantId : "tenant_default";
        this.eventType = eventType;
        this.payload = payload;
        this.signature = signature;
        this.status = status;
        this.processedAt = LocalDateTime.now();
        this.createdAt = LocalDateTime.now();
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
