package com.flyo.rescue.exception;

public class ProviderTimeoutException extends RuntimeException {
    private final String provider;
    private final String operation;

    public ProviderTimeoutException(String provider, String operation, String message) {
        super(message);
        this.provider = provider;
        this.operation = operation;
    }

    public String getProvider() {
        return provider;
    }

    public String getOperation() {
        return operation;
    }
}
