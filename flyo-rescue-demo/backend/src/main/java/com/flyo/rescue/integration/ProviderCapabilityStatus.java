package com.flyo.rescue.integration;

/**
 * Authoritative capability, certification, and connectivity state for external provider adapters.
 *
 * Enforces strict separation between sandbox tests and certified production operations:
 * - A sandbox success must NEVER be presented as production verification.
 * - An internal simulator must NEVER be presented as a real provider connection.
 */
public enum ProviderCapabilityStatus {
    NOT_CONFIGURED,               // Adapter recognized by canonical model, awaiting client credentials & endpoint
    CONFIGURED_NOT_VERIFIED,      // Contract implemented, awaiting client credentials or sandbox activation
    AUTHENTICATION_VERIFIED,      // OAuth2 / API key credentials verified with provider identity service
    CONNECTIVITY_VERIFIED,        // Network reachability, TLS handshake, and base health check verified
    SANDBOX_CONNECTED,            // Connected to live provider sandbox endpoint
    SANDBOX_CONTRACT_TESTED,      // Contract and payload verified against official provider sandbox specification
    SANDBOX_CONTRACT_VERIFIED,    // Contract schemas and error mappings verified against provider specification
    SANDBOX_OPERATION_VERIFIED,   // End-to-end sandbox business operation (booking/search/settlement) verified
    PRODUCTION_CONNECTED,         // Live production endpoint reachable with verified production credentials
    PRODUCTION_VERIFIED,          // Production operations certified with end-to-end reconciliation and SLA proof
    PRODUCTION_OPERATION_VERIFIED,// Live production business operations verified with end-to-end proof
    DEGRADED,                     // Experiencing latency breaches, error rate spikes, or upstream downtime
    DISABLED,                     // Administratively suspended or muted by operator command
    UNSUPPORTED                   // Capability not supported by adapter or underlying provider protocol
}
