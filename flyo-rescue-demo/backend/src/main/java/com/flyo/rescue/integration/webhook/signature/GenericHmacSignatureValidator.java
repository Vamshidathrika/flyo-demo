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
 * Validates partner webhooks using standard HMAC-SHA256 and optional timestamp drift header.
 */
@Component
public class GenericHmacSignatureValidator implements WebhookSignatureValidator {

    private static final Logger log = LoggerFactory.getLogger(GenericHmacSignatureValidator.class);
    private static final long DEFAULT_DRIFT_WINDOW_SECONDS = 300;

    @Override
    public String getProviderName() {
        return "generic";
    }

    @Override
    public boolean validate(String rawPayload, String signatureHeader, String secret, Map<String, String> headers) {
        if (rawPayload == null || signatureHeader == null || secret == null || secret.isBlank()) {
            return false;
        }

        // Optional timestamp header verification
        if (headers != null) {
            String ts = headers.getOrDefault("X-Flyo-Timestamp", headers.get("timestamp"));
            if (ts != null) {
                try {
                    long epoch = Long.parseLong(ts);
                    long now = Instant.now().getEpochSecond();
                    if (Math.abs(now - epoch) > DEFAULT_DRIFT_WINDOW_SECONDS) {
                        log.warn("[Generic Webhook Validator] Timestamp drift {}s exceeds allowed window", Math.abs(now - epoch));
                        return false;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        String expectedHex = signatureHeader.replace("sha256=", "").trim().toLowerCase();

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
            log.error("[Generic Webhook Validator] HMAC computation error: {}", ex.getMessage());
            return false;
        }
    }
}
