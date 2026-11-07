package com.flyo.rescue.integration.dto;

public class PaymentSettlementRequest {
    private String caseId;
    private String paymentToken;
    private int amount;
    private String merchantName;
    private String currency;
    private String idempotencyKey;

    public PaymentSettlementRequest() {}

    public PaymentSettlementRequest(String caseId, String paymentToken, int amount, String merchantName, String currency, String idempotencyKey) {
        this.caseId = caseId;
        this.paymentToken = paymentToken;
        this.amount = amount;
        this.merchantName = merchantName;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getPaymentToken() { return paymentToken; }
    public void setPaymentToken(String paymentToken) { this.paymentToken = paymentToken; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
