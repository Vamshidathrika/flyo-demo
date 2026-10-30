package com.flyo.rescue.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    private String id;
    private String caseId;
    private String virtualCardId;
    private int amount;
    private String currency; // "INR"
    private String vendor; // e.g. "StarSky Airlines / Air India"
    private String status; // "SETTLED", "PENDING", "FAILED"
    private String referenceId;
    private LocalDateTime timestamp;

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
}
