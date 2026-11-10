package com.flyo.rescue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RebookResultDto {
    private boolean success;
    private String caseId;
    private String optionId;
    private String flight;
    private String newPnr;
    private String providerOrderId;
    private String error;
    private int retryCount;
    private List<String> steps;
    private List<String> screenshots;

    public RebookResultDto() {
    }

    public RebookResultDto(boolean success, String caseId, String optionId, String flight, String newPnr,
                           String error, int retryCount, List<String> steps, List<String> screenshots) {
        this.success = success;
        this.caseId = caseId;
        this.optionId = optionId;
        this.flight = flight;
        this.newPnr = newPnr;
        this.error = error;
        this.retryCount = retryCount;
        this.steps = steps;
        this.screenshots = screenshots;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getOptionId() {
        return optionId;
    }

    public void setOptionId(String optionId) {
        this.optionId = optionId;
    }

    public String getFlight() {
        return flight;
    }

    public void setFlight(String flight) {
        this.flight = flight;
    }

    public String getNewPnr() {
        return newPnr;
    }

    public void setNewPnr(String newPnr) {
        this.newPnr = newPnr;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public List<String> getSteps() {
        return steps;
    }

    public void setSteps(List<String> steps) {
        this.steps = steps;
    }

    public List<String> getScreenshots() {
        return screenshots;
    }

    public void setScreenshots(List<String> screenshots) {
        this.screenshots = screenshots;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public void setProviderOrderId(String providerOrderId) {
        this.providerOrderId = providerOrderId;
    }
}
