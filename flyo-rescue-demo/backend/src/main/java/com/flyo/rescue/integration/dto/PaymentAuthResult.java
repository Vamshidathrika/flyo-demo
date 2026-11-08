package com.flyo.rescue.integration.dto;

public class PaymentAuthResult {
    private boolean authorized;
    private String paymentToken;
    private String last4;
    private String expiry;
    private int authorizedAmount;
    private String currency;
    private String authorizationCode;
    private String errorCode;
    private String errorMessage;

    public PaymentAuthResult() {}

    public static PaymentAuthResult success(String paymentToken, String last4, String expiry, int amount, String currency, String authCode) {
        PaymentAuthResult res = new PaymentAuthResult();
        res.authorized = true;
        res.paymentToken = paymentToken;
        res.last4 = last4;
        res.expiry = expiry;
        res.authorizedAmount = amount;
        res.currency = currency;
        res.authorizationCode = authCode;
        return res;
    }

    public static PaymentAuthResult failure(String errorCode, String errorMessage) {
        PaymentAuthResult res = new PaymentAuthResult();
        res.authorized = false;
        res.errorCode = errorCode;
        res.errorMessage = errorMessage;
        return res;
    }

    public boolean isAuthorized() { return authorized; }
    public void setAuthorized(boolean authorized) { this.authorized = authorized; }
    public String getPaymentToken() { return paymentToken; }
    public void setPaymentToken(String paymentToken) { this.paymentToken = paymentToken; }
    public String getLast4() { return last4; }
    public void setLast4(String last4) { this.last4 = last4; }
    public String getExpiry() { return expiry; }
    public void setExpiry(String expiry) { this.expiry = expiry; }
    public int getAuthorizedAmount() { return authorizedAmount; }
    public void setAuthorizedAmount(int authorizedAmount) { this.authorizedAmount = authorizedAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getAuthorizationCode() { return authorizationCode; }
    public void setAuthorizationCode(String authorizationCode) { this.authorizationCode = authorizationCode; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
