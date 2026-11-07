package com.flyo.rescue.exception;

public class ProviderRateLimitException extends RuntimeException {
    private final String provider;
    private final int retryAfterSeconds;

    public ProviderRateLimitException(String provider, String message, int retryAfterSeconds) {
        super(message);
        this.provider = provider;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getProvider() {
        return provider;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
