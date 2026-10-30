package com.flyo.rescue.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WhatsAppOtpService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppOtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int OTP_VALIDITY_MINUTES = 5;

    public record OtpChallenge(
            String phone,
            String caseId,
            String optionId,
            String code,
            Instant createdAt,
            Instant expiresAt,
            boolean verified
    ) {}

    // In-memory store: phone -> active OtpChallenge
    private final Map<String, OtpChallenge> activeOtps = new ConcurrentHashMap<>();

    /**
     * Generate a cryptographically random 6-digit OTP challenge for a passenger rescue approval.
     */
    public OtpChallenge createOtpChallenge(String phone, String caseId, String optionId) {
        String cleanPhone = cleanPhone(phone);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant now = Instant.now();
        Instant expiresAt = now.plus(OTP_VALIDITY_MINUTES, ChronoUnit.MINUTES);

        OtpChallenge challenge = new OtpChallenge(
                cleanPhone,
                caseId != null ? caseId : "C1",
                optionId != null ? optionId : "O2",
                code,
                now,
                expiresAt,
                false
        );

        activeOtps.put(cleanPhone, challenge);
        log.info("[WhatsApp Security OTP] Generated OTP {} for phone {} (Case: {}, Option: {}, Expires in {}m)",
                code, cleanPhone, caseId, optionId, OTP_VALIDITY_MINUTES);
        return challenge;
    }

    /**
     * Verify an OTP submitted via WhatsApp chat.
     */
    public Optional<OtpChallenge> verifyOtp(String phone, String submittedCode) {
        String cleanPhone = cleanPhone(phone);
        OtpChallenge challenge = activeOtps.get(cleanPhone);

        if (challenge == null) {
            log.warn("[WhatsApp Security OTP] Verification failed: No active OTP found for phone {}", cleanPhone);
            return Optional.empty();
        }

        if (Instant.now().isAfter(challenge.expiresAt())) {
            log.warn("[WhatsApp Security OTP] Verification failed: OTP expired for phone {}", cleanPhone);
            activeOtps.remove(cleanPhone);
            return Optional.empty();
        }

        if (submittedCode == null || !submittedCode.trim().equals(challenge.code())) {
            log.warn("[WhatsApp Security OTP] Mismatch: submitted='{}', expected='{}'", submittedCode, challenge.code());
            return Optional.empty();
        }

        // Mark verified and remove from active
        OtpChallenge verifiedChallenge = new OtpChallenge(
                challenge.phone(),
                challenge.caseId(),
                challenge.optionId(),
                challenge.code(),
                challenge.createdAt(),
                challenge.expiresAt(),
                true
        );
        activeOtps.remove(cleanPhone);
        log.info("[WhatsApp Security OTP] OTP successfully verified for phone {} (Case: {}, Option: {})",
                cleanPhone, challenge.caseId(), challenge.optionId());
        return Optional.of(verifiedChallenge);
    }

    public Optional<OtpChallenge> getActiveOtp(String phone) {
        String cleanPhone = cleanPhone(phone);
        OtpChallenge challenge = activeOtps.get(cleanPhone);
        if (challenge != null && Instant.now().isBefore(challenge.expiresAt())) {
            return Optional.of(challenge);
        }
        return Optional.empty();
    }

    public void clear(String phone) {
        if (phone != null) {
            activeOtps.remove(cleanPhone(phone));
        } else {
            activeOtps.clear();
        }
    }

    private String cleanPhone(String phone) {
        if (phone == null || phone.isBlank()) return "919876543210";
        return phone.replaceAll("[^0-9]", "");
    }
}
