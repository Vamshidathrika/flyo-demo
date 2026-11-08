package com.flyo.rescue.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class MetaSignatureVerifier {

    @Autowired(required = false)
    private Environment environment;

    public boolean verifySignature(String payload, String signatureHeader, String appSecret) {
        if (appSecret == null || appSecret.isBlank()) {
            if (environment != null && environment.matchesProfiles("prod")) {
                return false; // Fail closed in production: unauthenticated webhooks are rejected
            }
            return true; // Dev / Mock mode for isolated test suites
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }

        String expectedHash = signatureHeader.substring("sha256=".length()).trim();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b));
            }
            String calculatedHash = sb.toString();

            return MessageDigest.isEqual(
                    calculatedHash.getBytes(StandardCharsets.UTF_8),
                    expectedHash.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }
}
