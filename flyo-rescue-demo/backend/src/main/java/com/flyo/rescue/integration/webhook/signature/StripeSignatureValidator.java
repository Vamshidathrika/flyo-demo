package com.flyo.rescue.integration.webhook.signature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Validates Stripe webhooks per official Stripe API specifications:
 * - Header: Stripe-Signature
 * - Format: t={timestamp},v1={hex_signature}[,v0={...}]
 * - Replay tolerance: Rejects timestamps older than 300 seconds (5 minutes).
 * - Algorithm: HMAC-SHA256 computed over "{timestamp}.{rawPayload}" using Stripe Webhook Signing Secret.
 * - Verification: Constant-time comparison on v1 scheme.
 */
@Component
public class StripeSignatureValidator implements WebhookSignatureValidator {

    private static final Logger log = LoggerFactory.getLogger(StripeSignatureValidator.class);
    private static final long DEFAULT_TOLERANCE_SECONDS = 300;

    @Override
    public String getProviderName() {
        return "stripe";
    }

    @Override
    public boolean validate(String rawPayload, String signatureHeader, String secret, Map<String, String> headers) {
        if (rawPayload == null || signatureHeader == null || secret == null || secret.isBlank()) {
            return false;
        }

        // Parse header components: t=123456,v1=abcdef...
        String[] parts = signatureHeader.split(",");
        String timestampStr = null;
        String v1Signature = null;

        for (String part : parts) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                if ("t".equalsIgnoreCase(kv[0])) {
                    timestampStr = kv[1].trim();
                } else if ("v1".equalsIgnoreCase(kv[0])) {
                    v1Signature = kv[1].trim().toLowerCase();
                }
            }
        }

        if (timestampStr == null || v1Signature == null) {
            log.warn("[Stripe Webhook Validator] Missing timestamp or v1 signature component in header: {}", signatureHeader);
            return false;
        }

        long timestampEpoch;
        try {
            timestampEpoch = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            log.warn("[Stripe Webhook Validator] Invalid timestamp format: {}", timestampStr);
            return false;
        }

        // Replay Protection: Reject requests outside 300-second window
        long currentEpoch = Instant.now().getEpochSecond();
        if (Math.abs(currentEpoch - timestampEpoch) > DEFAULT_TOLERANCE_SECONDS) {
            log.warn("[Stripe Webhook Validator] Webhook rejected: timestamp {} drifts {}s from current time {}",
                    timestampEpoch, Math.abs(currentEpoch - timestampEpoch), currentEpoch);
            return false;
        }

        // Compute HMAC over "{timestamp}.{rawPayload}"
        String signedPayload = timestampStr + "." + rawPayload;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);

            byte[] hmacBytes = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            String calculatedHex = HexFormat.of().formatHex(hmacBytes).toLowerCase();

            return MessageDigest.isEqual(
                    v1Signature.getBytes(StandardCharsets.UTF_8),
                    calculatedHex.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception ex) {
            log.error("[Stripe Webhook Validator] HMAC computation error: {}", ex.getMessage());
            return false;
        }
    }
}
