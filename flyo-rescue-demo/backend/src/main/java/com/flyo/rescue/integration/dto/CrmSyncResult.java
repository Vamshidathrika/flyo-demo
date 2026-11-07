package com.flyo.rescue.integration.dto;

public class CrmSyncResult {
    private boolean synced;
    private String crmTicketId;
    private String crmSystem; // "SALESFORCE", "ZENDESK", "HUBSPOT"
    private String errorMessage;

    public CrmSyncResult() {}

    public static CrmSyncResult success(String crmTicketId, String crmSystem) {
        CrmSyncResult res = new CrmSyncResult();
        res.synced = true;
        res.crmTicketId = crmTicketId;
        res.crmSystem = crmSystem;
        return res;
    }

    public static CrmSyncResult failure(String crmSystem, String errorMessage) {
        CrmSyncResult res = new CrmSyncResult();
        res.synced = false;
        res.crmSystem = crmSystem;
        res.errorMessage = errorMessage;
        return res;
    }

    public boolean isSynced() { return synced; }
    public boolean isSuccess() { return synced; }
    public String getExternalSyncReference() { return crmTicketId; }
    public void setSynced(boolean synced) { this.synced = synced; }
    public String getCrmTicketId() { return crmTicketId; }
    public void setCrmTicketId(String crmTicketId) { this.crmTicketId = crmTicketId; }
    public String getCrmSystem() { return crmSystem; }
    public void setCrmSystem(String crmSystem) { this.crmSystem = crmSystem; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
