package com.flyo.rescue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Tokenized Corporate Virtual Card Entity.
 *
 * Compliance Target: PCI-DSS v4.0 Requirement 3.2.
 * Strictly avoids storing Sensitive Authentication Data (SAD / CVV) or raw PANs.
 * Only stores the provider token reference and masked last 4 digits.
 */
@Entity
@Table(name = "virtual_cards")
public class VirtualCard {

    @Id
    private String id;
    private String caseId;
    private String optionId;

    @Column(name = "tenant_id")
    private String tenantId = "tenant_default";

    @Column(name = "card_token_id", nullable = false, unique = true)
    private String cardTokenId;

    @Column(name = "last_four", nullable = false, length = 4)
    private String lastFour;

    @Column(name = "card_brand")
    private String cardBrand;

    private String cardHolder;
    private String expiry;

    private int amount;
    private String currency; // "INR"
    private String status; // "ISSUED", "CHARGED", "CANCELLED"
    private String merchantCategory; // "3000 - Airlines"
    private LocalDateTime issuedAt;

    public VirtualCard() {
    }

    public VirtualCard(String id, String caseId, String optionId, String cardTokenId,
                       String lastFour, String cardBrand, String cardHolder, String expiry,
                       int amount, String currency, String status, String merchantCategory,
                       LocalDateTime issuedAt) {
        this.id = id;
        this.caseId = caseId;
        this.optionId = optionId;
        this.cardTokenId = cardTokenId;
        this.lastFour = lastFour;
        this.cardBrand = cardBrand != null ? cardBrand : "Mastercard";
        this.cardHolder = cardHolder;
        this.expiry = expiry;
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

    public String getTenantId() {
        return tenantId != null ? tenantId : "tenant_default";
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getOptionId() {
        return optionId;
    }

    public void setOptionId(String optionId) {
        this.optionId = optionId;
    }

    public String getCardTokenId() {
        return cardTokenId;
    }

    public void setCardTokenId(String cardTokenId) {
        this.cardTokenId = cardTokenId;
    }

    public String getLastFour() {
        return lastFour;
    }

    public void setLastFour(String lastFour) {
        this.lastFour = lastFour;
    }

    public String getCardBrand() {
        return cardBrand;
    }

    public void setCardBrand(String cardBrand) {
        this.cardBrand = cardBrand;
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
        if (lastFour != null && !lastFour.isBlank()) {
            return "•••• •••• •••• " + lastFour;
        }
        return "•••• •••• •••• ••••";
    }

    /**
     * Deprecated compatibility getter returning masked card number.
     * Annotated with @JsonIgnore to ensure raw or masked PAN is never exposed under the "cardNumber" JSON key.
     */
    @Deprecated
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getCardNumber() {
        return getMaskedCardNumber();
    }
}
