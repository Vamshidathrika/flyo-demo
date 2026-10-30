package com.flyo.rescue.exception;

public class ProviderNotConfiguredException extends RuntimeException {
    private final String providerName;

    public ProviderNotConfiguredException(String providerName, String message) {
        super(message);
        this.providerName = providerName;
    }

    public String getProviderName() {
        return providerName;
    }
}
