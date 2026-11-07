package com.flyo.rescue.exception;

public class ProviderAuthenticationException extends RuntimeException {
    private final String provider;

    public ProviderAuthenticationException(String provider, String message) {
        super(message);
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
    }
}
