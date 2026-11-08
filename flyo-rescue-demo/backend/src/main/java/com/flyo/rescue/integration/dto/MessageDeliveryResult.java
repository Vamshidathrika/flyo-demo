package com.flyo.rescue.integration.dto;

public class MessageDeliveryResult {
    private boolean delivered;
    private String messageId;
    private String channel;
    private String status;
    private String errorMessage;

    public MessageDeliveryResult() {}

    public static MessageDeliveryResult success(String messageId, String channel) {
        MessageDeliveryResult res = new MessageDeliveryResult();
        res.delivered = true;
        res.messageId = messageId;
        res.channel = channel;
        res.status = "DELIVERED";
        return res;
    }

    public static MessageDeliveryResult failure(String channel, String errorMessage) {
        MessageDeliveryResult res = new MessageDeliveryResult();
        res.delivered = false;
        res.channel = channel;
        res.status = "FAILED";
        res.errorMessage = errorMessage;
        return res;
    }

    public boolean isDelivered() { return delivered; }
    public void setDelivered(boolean delivered) { this.delivered = delivered; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
