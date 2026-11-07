package com.flyo.rescue.integration.webhook.signature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Validates Meta WhatsApp Cloud API webhooks per Meta Developer Documentation:
 * - Header: X-Hub-Signature-256
 * - Format: sha256={64-hex-character-hash}
 * - Algorithm: HMAC-SHA256 computed over raw UTF-8 request payload bytes using Meta App Secret.
 * - Verification: Constant-time byte comparison to prevent timing side-channel attacks.
 */
@Component
public class MetaWhatsAppSignatureValidator implements WebhookSignatureValidator {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppSignatureValidator.class);
    private static final String SHA256_PREFIX = "sha256=";

    @Override
    public String getProviderName() {
        return "meta-whatsapp";
    }

    @Override
    public boolean validate(String rawPayload, String signatureHeader, String secret, Map<String, String> headers) {
        if (rawPayload == null || signatureHeader == null || secret == null || secret.isBlank()) {
            return false;
        }

        String actualSignature = signatureHeader.trim();
        if (!actualSignature.startsWith(SHA256_PREFIX)) {
            log.warn("[Meta Webhook Validator] Invalid signature format (missing 'sha256=' prefix): {}", signatureHeader);
            return false;
        }

        String expectedHex = actualSignature.substring(SHA256_PREFIX.length()).trim().toLowerCase();

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);

            byte[] hmacBytes = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            String calculatedHex = HexFormat.of().formatHex(hmacBytes).toLowerCase();

            return MessageDigest.isEqual(
                    expectedHex.getBytes(StandardCharsets.UTF_8),
                    calculatedHex.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception ex) {
            log.error("[Meta Webhook Validator] HMAC computation error: {}", ex.getMessage());
            return false;
        }
    }
}
