package com.flyo.rescue.dto;

public class RejectRequest {
    private String reason;
    private String rejectedBy;

    public RejectRequest() {
    }

    public RejectRequest(String reason, String rejectedBy) {
        this.reason = reason;
        this.rejectedBy = rejectedBy;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    public void setRejectedBy(String rejectedBy) {
        this.rejectedBy = rejectedBy;
    }
}
