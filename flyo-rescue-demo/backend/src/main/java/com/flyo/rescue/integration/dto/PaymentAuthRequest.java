package com.flyo.rescue.integration.dto;

public class PaymentAuthRequest {
    private String caseId;
    private int amount;
    private String currency;
    private String merchantCategoryCode; // e.g. "3001"
    private String merchantName;
    private String idempotencyKey;

    public PaymentAuthRequest() {}

    public PaymentAuthRequest(String caseId, int amount, String currency, String merchantCategoryCode, String merchantName, String idempotencyKey) {
        this.caseId = caseId;
        this.amount = amount;
        this.currency = currency;
        this.merchantCategoryCode = merchantCategoryCode;
        this.merchantName = merchantName;
        this.idempotencyKey = idempotencyKey;
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getMerchantCategoryCode() { return merchantCategoryCode; }
    public void setMerchantCategoryCode(String merchantCategoryCode) { this.merchantCategoryCode = merchantCategoryCode; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
