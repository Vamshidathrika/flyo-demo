package com.flyo.rescue.integration.dto;

public class MessageDeliveryRequest {
    private String caseId;
    private String channel; // "WHATSAPP", "SMS", "EMAIL"
    private String recipient;
    private String templateName;
    private String messageBody;
    private String idempotencyKey;

    public MessageDeliveryRequest() {}

    public MessageDeliveryRequest(String caseId, String channel, String recipient, String templateName, String messageBody, String idempotencyKey) {
        this.caseId = caseId;
        this.channel = channel;
        this.recipient = recipient;
        this.templateName = templateName;
        this.messageBody = messageBody;
        this.idempotencyKey = idempotencyKey;
    }

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getMessageBody() { return messageBody; }
    public void setMessageBody(String messageBody) { this.messageBody = messageBody; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
