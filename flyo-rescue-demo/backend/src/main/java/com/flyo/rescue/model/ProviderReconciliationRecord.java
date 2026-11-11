package com.flyo.rescue.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "provider_reconciliation_records")
public class ProviderReconciliationRecord {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "booking_id", length = 64, nullable = false)
    private String bookingId;

    @Column(name = "provider_name", length = 64, nullable = false)
    private String providerName;

    @Column(name = "provider_order_id", length = 128)
    private String providerOrderId;

    @Column(name = "correlation_id", length = 64, nullable = false)
    private String correlationId;

    @Column(name = "disruption_flight", length = 32)
    private String disruptionFlight;

    @Column(name = "recovery_flight", length = 32)
    private String recoveryFlight;

    @Column(name = "status", length = 32, nullable = false)
    private String status = "PENDING";

    @Column(name = "discrepancy_details", columnDefinition = "TEXT")
    private String discrepancyDetails;

    @Column(name = "reconciled_pnr", length = 32)
    private String reconciledPnr;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    public ProviderReconciliationRecord() {
    }

    public ProviderReconciliationRecord(String id, String caseId, String bookingId, String providerName,
                                        String providerOrderId, String correlationId, String disruptionFlight,
                                        String recoveryFlight, String status, String discrepancyDetails) {
        this.id = id;
        this.caseId = caseId;
        this.bookingId = bookingId;
        this.providerName = providerName;
        this.providerOrderId = providerOrderId;
        this.correlationId = correlationId;
        this.disruptionFlight = disruptionFlight;
        this.recoveryFlight = recoveryFlight;
        this.status = status;
        this.discrepancyDetails = discrepancyDetails;
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

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public void setProviderOrderId(String providerOrderId) {
        this.providerOrderId = providerOrderId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getDisruptionFlight() {
        return disruptionFlight;
    }

    public void setDisruptionFlight(String disruptionFlight) {
        this.disruptionFlight = disruptionFlight;
    }

    public String getRecoveryFlight() {
        return recoveryFlight;
    }

    public void setRecoveryFlight(String recoveryFlight) {
        this.recoveryFlight = recoveryFlight;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDiscrepancyDetails() {
        return discrepancyDetails;
    }

    public void setDiscrepancyDetails(String discrepancyDetails) {
        this.discrepancyDetails = discrepancyDetails;
    }

    public String getReconciledPnr() {
        return reconciledPnr;
    }

    public void setReconciledPnr(String reconciledPnr) {
        this.reconciledPnr = reconciledPnr;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getReconciledAt() {
        return reconciledAt;
    }

    public void setReconciledAt(LocalDateTime reconciledAt) {
        this.reconciledAt = reconciledAt;
    }
}
