package com.flyo.rescue.dto;

public class ApproveRequest {
    private String optionId;
    private String approvedBy;

    public ApproveRequest() {
    }

    public ApproveRequest(String optionId, String approvedBy) {
        this.optionId = optionId;
        this.approvedBy = approvedBy;
    }

    public String getOptionId() {
        return optionId;
    }

    public void setOptionId(String optionId) {
        this.optionId = optionId;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }
}
