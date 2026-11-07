package com.flyo.rescue.service.provider;

import java.time.LocalDateTime;

/**
 * Standardized envelope for all external airline, GDS, and payment provider responses.
 */
public class ExternalProviderResponse<T> {

    private final ProviderOutcomeStatus status;
    private final String providerName;
    private final String providerReference; // e.g. External PNR or VCC Txn ID
    private final T data;
    private final String errorCode;
    private final String errorMessage;
    private final boolean retryable;
    private final LocalDateTime timestamp;

    public ExternalProviderResponse(
            ProviderOutcomeStatus status,
            String providerName,
            String providerReference,
            T data,
            String errorCode,
            String errorMessage,
            boolean retryable) {
        this.status = status;
        this.providerName = providerName;
        this.providerReference = providerReference;
        this.data = data;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.retryable = retryable;
        this.timestamp = LocalDateTime.now();
    }

    public static <T> ExternalProviderResponse<T> success(String providerName, String reference, T data) {
        return new ExternalProviderResponse<>(ProviderOutcomeStatus.CONFIRMED_SUCCESS, providerName, reference, data, null, null, false);
    }

    public static <T> ExternalProviderResponse<T> failure(String providerName, String errorCode, String errorMessage, boolean retryable) {
        return new ExternalProviderResponse<>(ProviderOutcomeStatus.CONFIRMED_FAILURE, providerName, null, null, errorCode, errorMessage, retryable);
    }

    public static <T> ExternalProviderResponse<T> timeout(String providerName, String message) {
        return new ExternalProviderResponse<>(ProviderOutcomeStatus.TIMEOUT_UNKNOWN, providerName, null, null, "TIMEOUT_UNKNOWN", message, false);
    }

    public static <T> ExternalProviderResponse<T> malformed(String providerName, String details) {
        return new ExternalProviderResponse<>(ProviderOutcomeStatus.MALFORMED_RESPONSE, providerName, null, null, "MALFORMED_RESPONSE", details, false);
    }

    public static <T> ExternalProviderResponse<T> unsupported(String providerName, String operation) {
        return new ExternalProviderResponse<>(ProviderOutcomeStatus.UNSUPPORTED_OPERATION, providerName, null, null, "UNSUPPORTED_OPERATION",
                "Operation '" + operation + "' is not supported by " + providerName, false);
    }

    public ProviderOutcomeStatus getStatus() {
        return status;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public T getData() {
        return data;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public boolean isConfirmedSuccess() {
        return status == ProviderOutcomeStatus.CONFIRMED_SUCCESS;
    }

    public boolean isConfirmedFailure() {
        return status == ProviderOutcomeStatus.CONFIRMED_FAILURE;
    }

    public boolean isAmbiguous() {
        return status == ProviderOutcomeStatus.TIMEOUT_UNKNOWN || status == ProviderOutcomeStatus.MALFORMED_RESPONSE;
    }
}
