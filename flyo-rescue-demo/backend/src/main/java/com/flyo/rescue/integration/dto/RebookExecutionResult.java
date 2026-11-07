package com.flyo.rescue.integration.dto;

public class RebookExecutionResult {
    private boolean success;
    private String newPnr;
    private String eTicketNumber;
    private String carrier;
    private String flight;
    private String errorCode;
    private String errorMessage;
    private String providerTransactionId;

    public RebookExecutionResult() {}

    public static RebookExecutionResult successful(String newPnr, String eTicketNumber, String carrier, String flight, String txnId) {
        RebookExecutionResult res = new RebookExecutionResult();
        res.success = true;
        res.newPnr = newPnr;
        res.eTicketNumber = eTicketNumber;
        res.carrier = carrier;
        res.flight = flight;
        res.providerTransactionId = txnId;
        return res;
    }

    public static RebookExecutionResult failed(String errorCode, String errorMessage) {
        RebookExecutionResult res = new RebookExecutionResult();
        res.success = false;
        res.errorCode = errorCode;
        res.errorMessage = errorMessage;
        return res;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getNewPnr() { return newPnr; }
    public void setNewPnr(String newPnr) { this.newPnr = newPnr; }
    public String getETicketNumber() { return eTicketNumber; }
    public void setETicketNumber(String eTicketNumber) { this.eTicketNumber = eTicketNumber; }
    public String getCarrier() { return carrier; }
    public void setCarrier(String carrier) { this.carrier = carrier; }
    public String getFlight() { return flight; }
    public void setFlight(String flight) { this.flight = flight; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public void setProviderTransactionId(String providerTransactionId) { this.providerTransactionId = providerTransactionId; }
}
