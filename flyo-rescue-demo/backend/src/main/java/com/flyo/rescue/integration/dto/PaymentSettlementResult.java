package com.flyo.rescue.integration.dto;

public class PaymentSettlementResult {
    private boolean settled;
    private String transactionId;
    private int settledAmount;
    private String currency;
    private String status;
    private String errorMessage;

    public PaymentSettlementResult() {}

    public static PaymentSettlementResult success(String transactionId, int settledAmount, String currency) {
        PaymentSettlementResult res = new PaymentSettlementResult();
        res.settled = true;
        res.transactionId = transactionId;
        res.settledAmount = settledAmount;
        res.currency = currency;
        res.status = "CAPTURED";
        return res;
    }

    public static PaymentSettlementResult failure(String errorMessage) {
        PaymentSettlementResult res = new PaymentSettlementResult();
        res.settled = false;
        res.status = "SETTLEMENT_FAILED";
        res.errorMessage = errorMessage;
        return res;
    }

    public boolean isSettled() { return settled; }
    public void setSettled(boolean settled) { this.settled = settled; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public int getSettledAmount() { return settledAmount; }
    public void setSettledAmount(int settledAmount) { this.settledAmount = settledAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
