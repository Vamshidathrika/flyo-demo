package com.flyo.rescue.service.provider;

/**
 * Explicit taxonomy for external third-party provider outcomes.
 *
 * Strictly separates confirmed terminal states from ambiguous network timeouts
 * to prevent blind retries and double-booking incidents.
 */
public enum ProviderOutcomeStatus {

    /**
     * The external provider confirmed the operation completed successfully (e.g. seat booked, auth granted).
     */
    CONFIRMED_SUCCESS,

    /**
     * The external provider confirmed the operation was rejected (e.g. flight full, card declined, fare expired).
     */
    CONFIRMED_FAILURE,

    /**
     * Network timeout, socket disconnect, or HTTP 504.
     * The true outcome at the provider is UNKNOWN and MUST be reconciled before any retry or compensation.
     */
    TIMEOUT_UNKNOWN,

    /**
     * Provider returned HTTP 200/500 with unparseable or schema-violating payload.
     */
    MALFORMED_RESPONSE,

    /**
     * The requested capability is not supported by the provider (e.g. carrier does not support online void).
     */
    UNSUPPORTED_OPERATION
}
