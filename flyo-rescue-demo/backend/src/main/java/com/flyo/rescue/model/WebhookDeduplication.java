package com.flyo.rescue.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "webhook_deduplications", uniqueConstraints = {
        @UniqueConstraint(name = "uq_webhook_provider_event", columnNames = {"provider", "event_id"})
})
public class WebhookDeduplication {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 64, nullable = false)
    private String provider;

    @Column(name = "event_id", length = 128, nullable = false)
    private String eventId;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @Column(name = "payload_hash", length = 128)
    private String payloadHash;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "tenant_default";

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(length = 32, nullable = false)
    private String status = "PROCESSED";

    public WebhookDeduplication() {
    }

    public WebhookDeduplication(String id, String provider, String eventId, String eventType, String payloadHash, String tenantId) {
        this.id = id;
        this.provider = provider;
        this.eventId = eventId;
        this.eventType = eventType;
        this.payloadHash = payloadHash;
        this.tenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId : "tenant_default";
        this.receivedAt = LocalDateTime.now();
        this.processedAt = LocalDateTime.now();
        this.status = "PROCESSED";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
