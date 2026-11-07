package com.flyo.rescue.integration.dto;

public class RebookExecutionRequest {
    private String caseId;
    private String optionId;
    private String targetFlight;
    private String passengerName;
    private String passengerPhone;
    private String passengerEmail;
    private String paymentToken;
    private int amount;
    private String currency;
    private String idempotencyKey;

    public RebookExecutionRequest() {}

    public RebookExecutionRequest(String caseId, String optionId, String targetFlight,
                                 String passengerName, String passengerPhone, String passengerEmail,
                                 String paymentToken, int amount, String currency, String idempotencyKey) {
        this.caseId = caseId;
        this.optionId = optionId;
        this.targetFlight = targetFlight;
        this.passengerName = passengerName;
        this.passengerPhone = passengerPhone;
        this.passengerEmail = passengerEmail;
        this.paymentToken = paymentToken;
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getOptionId() { return optionId; }
    public void setOptionId(String optionId) { this.optionId = optionId; }
    public String getTargetFlight() { return targetFlight; }
    public void setTargetFlight(String targetFlight) { this.targetFlight = targetFlight; }
    public String getPassengerName() { return passengerName; }
    public void setPassengerName(String passengerName) { this.passengerName = passengerName; }
    public String getPassengerPhone() { return passengerPhone; }
    public void setPassengerPhone(String passengerPhone) { this.passengerPhone = passengerPhone; }
    public String getPassengerEmail() { return passengerEmail; }
    public void setPassengerEmail(String passengerEmail) { this.passengerEmail = passengerEmail; }
    public String getPaymentToken() { return paymentToken; }
    public void setPaymentToken(String paymentToken) { this.paymentToken = paymentToken; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
