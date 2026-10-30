package com.flyo.rescue.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "virtual_cards")
public class VirtualCard {

    @Id
    private String id;
    private String caseId;
    private String optionId;

    @JsonIgnore
    private String cardNumber;
    private String cardHolder;
    private String expiry;

    @JsonIgnore
    private String cvv;
    private int amount;
    private String currency; // "INR"
    private String status; // "ISSUED", "CHARGED", "CANCELLED"
    private String merchantCategory; // "3000 - Airlines"
    private LocalDateTime issuedAt;

    public VirtualCard() {
    }

    public VirtualCard(String id, String caseId, String optionId, String cardNumber,
                       String cardHolder, String expiry, String cvv, int amount,
                       String currency, String status, String merchantCategory,
                       LocalDateTime issuedAt) {
        this.id = id;
        this.caseId = caseId;
        this.optionId = optionId;
        this.cardNumber = cardNumber;
        this.cardHolder = cardHolder;
        this.expiry = expiry;
        this.cvv = cvv;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.merchantCategory = merchantCategory;
        this.issuedAt = issuedAt;
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

    public String getOptionId() {
        return optionId;
    }

    public void setOptionId(String optionId) {
        this.optionId = optionId;
    }

    @JsonIgnore
    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public String getCardHolder() {
        return cardHolder;
    }

    public void setCardHolder(String cardHolder) {
        this.cardHolder = cardHolder;
    }

    public String getExpiry() {
        return expiry;
    }

    public void setExpiry(String expiry) {
        this.expiry = expiry;
    }

    @JsonIgnore
    public String getCvv() {
        return cvv;
    }

    public void setCvv(String cvv) {
        this.cvv = cvv;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMerchantCategory() {
        return merchantCategory;
    }

    public void setMerchantCategory(String merchantCategory) {
        this.merchantCategory = merchantCategory;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(LocalDateTime issuedAt) {
        this.issuedAt = issuedAt;
    }

    @JsonProperty("maskedCardNumber")
    public String getMaskedCardNumber() {
        if (cardNumber != null && cardNumber.length() >= 4) {
            return "•••• •••• •••• " + cardNumber.substring(cardNumber.length() - 4);
        }
        return "•••• •••• •••• ••••";
    }
}
