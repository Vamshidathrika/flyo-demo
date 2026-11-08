package com.flyo.rescue.integration.dto;

import java.util.Map;

public class CrmSyncRequest {
    private String caseId;
    private String tenantId;
    private String passengerName;
    private String pnr;
    private String disruptionStatus;
    private String resolutionAction;
    private Map<String, String> customAttributes;

    public CrmSyncRequest() {}

    public CrmSyncRequest(String caseId, String pnr, String passengerName, String disruptionStatus, String resolutionAction) {
        this(caseId, "tenant_default", passengerName, pnr, disruptionStatus, resolutionAction, java.util.Map.of());
    }

    public CrmSyncRequest(String caseId, String tenantId, String passengerName, String pnr,
                          String disruptionStatus, String resolutionAction, Map<String, String> customAttributes) {
        this.caseId = caseId;
        this.tenantId = tenantId;
        this.passengerName = passengerName;
        this.pnr = pnr;
        this.disruptionStatus = disruptionStatus;
        this.resolutionAction = resolutionAction;
        this.customAttributes = customAttributes;
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPassengerName() { return passengerName; }
    public void setPassengerName(String passengerName) { this.passengerName = passengerName; }
    public String getPnr() { return pnr; }
    public void setPnr(String pnr) { this.pnr = pnr; }
    public String getDisruptionStatus() { return disruptionStatus; }
    public void setDisruptionStatus(String disruptionStatus) { this.disruptionStatus = disruptionStatus; }
    public String getResolutionAction() { return resolutionAction; }
    public void setResolutionAction(String resolutionAction) { this.resolutionAction = resolutionAction; }
    public Map<String, String> getCustomAttributes() { return customAttributes; }
    public void setCustomAttributes(Map<String, String> customAttributes) { this.customAttributes = customAttributes; }
}
