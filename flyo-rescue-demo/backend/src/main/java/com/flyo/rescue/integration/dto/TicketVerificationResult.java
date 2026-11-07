package com.flyo.rescue.integration.dto;

public class TicketVerificationResult {
    private boolean valid;
    private String pnr;
    private String ticketNumber;
    private String couponStatus;
    private String issuingCarrier;
    private String remarks;

    public TicketVerificationResult() {}

    public TicketVerificationResult(boolean valid, String pnr, String ticketNumber,
                                    String couponStatus, String issuingCarrier, String remarks) {
        this.valid = valid;
        this.pnr = pnr;
        this.ticketNumber = ticketNumber;
        this.couponStatus = couponStatus;
        this.issuingCarrier = issuingCarrier;
        this.remarks = remarks;
    }

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public String getPnr() { return pnr; }
    public void setPnr(String pnr) { this.pnr = pnr; }
    public String getTicketNumber() { return ticketNumber; }
    public void setTicketNumber(String ticketNumber) { this.ticketNumber = ticketNumber; }
    public String getCouponStatus() { return couponStatus; }
    public void setCouponStatus(String couponStatus) { this.couponStatus = couponStatus; }
    public String getIssuingCarrier() { return issuingCarrier; }
    public void setIssuingCarrier(String issuingCarrier) { this.issuingCarrier = issuingCarrier; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }
}
