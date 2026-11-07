package com.flyo.rescue.integration.webhook.signature;

import java.util.Map;

/**
 * Enterprise contract for provider-specific webhook cryptographic signature verification.
 */
public interface WebhookSignatureValidator {

    /**
     * Unique identifier for the provider (e.g., "meta-whatsapp", "stripe", "generic").
     */
    String getProviderName();

    /**
     * Validates an incoming webhook payload and headers according to the provider's official security standard.
     *
     * @param rawPayload      Raw string or bytes received in the request body
     * @param signatureHeader Value of the provider's signature header (e.g. X-Hub-Signature-256, Stripe-Signature)
     * @param secret          Registered provider signing secret
     * @param headers         Map of all HTTP request headers (used for timestamp or version extraction)
     * @return true if authentic; false if forged, mismatched, or replayed
     */
    boolean validate(String rawPayload, String signatureHeader, String secret, Map<String, String> headers);
}
