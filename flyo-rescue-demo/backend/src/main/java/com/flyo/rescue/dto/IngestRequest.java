package com.flyo.rescue.dto;

public class IngestRequest {
    private String rawText;
    private String source;

    public IngestRequest() {
    }

    public IngestRequest(String rawText, String source) {
        this.rawText = rawText;
        this.source = source;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
