package com.flyo.rescue.exception;

public class ProviderBusinessRuleException extends RuntimeException {
    private final String provider;
    private final String errorCode;

    public ProviderBusinessRuleException(String provider, String errorCode, String message) {
        super(message);
        this.provider = provider;
        this.errorCode = errorCode;
    }

    public String getProvider() {
        return provider;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
