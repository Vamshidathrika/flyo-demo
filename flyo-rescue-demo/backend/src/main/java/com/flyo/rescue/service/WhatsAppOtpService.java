package com.flyo.rescue.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;

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
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockoutUntil = new ConcurrentHashMap<>();

    /**
     * Check if a phone number is currently rate-limited or locked out.
     */
    public boolean isLockedOut(String phone) {
        String cleanPhone = cleanPhone(phone);
        Instant lockout = lockoutUntil.get(cleanPhone);
        if (lockout != null) {
            if (Instant.now().isBefore(lockout)) {
                return true;
            } else {
                lockoutUntil.remove(cleanPhone);
                failedAttempts.remove(cleanPhone);
            }
        }
        return false;
    }

    /**
     * Generate a cryptographically random 6-digit OTP challenge for a passenger rescue approval.
     */
    public OtpChallenge createOtpChallenge(String phone, String caseId, String optionId) {
        String cleanPhone = cleanPhone(phone);
        if (isLockedOut(cleanPhone)) {
            log.warn("[WhatsApp Security OTP] Phone {} is locked out due to excessive failed attempts.", cleanPhone);
            throw new IllegalStateException("Account locked out due to excessive failed OTP attempts. Try again later.");
        }

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
     * Verify an OTP submitted via WhatsApp chat with constant-time comparison to prevent timing attacks.
     */
    public Optional<OtpChallenge> verifyOtp(String phone, String submittedCode) {
        String cleanPhone = cleanPhone(phone);
        if (isLockedOut(cleanPhone)) {
            log.warn("[WhatsApp Security OTP] Verification rejected: Phone {} is locked out.", cleanPhone);
            return Optional.empty();
        }

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

        // Constant-time comparison to defend against timing side-channel attacks
        boolean matches = false;
        if (submittedCode != null && !submittedCode.isBlank()) {
            byte[] a = submittedCode.trim().getBytes(StandardCharsets.UTF_8);
            byte[] b = challenge.code().getBytes(StandardCharsets.UTF_8);
            matches = MessageDigest.isEqual(a, b);
        }

        if (!matches) {
            int failures = failedAttempts.merge(cleanPhone, 1, Integer::sum);
            log.warn("[WhatsApp Security OTP] Mismatch for phone {}: failure count = {}/{}", cleanPhone, failures, MAX_FAILED_ATTEMPTS);
            if (failures >= MAX_FAILED_ATTEMPTS) {
                Instant lockout = Instant.now().plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES);
                lockoutUntil.put(cleanPhone, lockout);
                activeOtps.remove(cleanPhone);
                log.error("[WhatsApp Security OTP] Phone {} locked out until {} due to {} consecutive failed attempts", cleanPhone, lockout, failures);
            }
            return Optional.empty();
        }

        // Reset failed attempts on success
        failedAttempts.remove(cleanPhone);

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
            String clean = cleanPhone(phone);
            activeOtps.remove(clean);
            failedAttempts.remove(clean);
            lockoutUntil.remove(clean);
        } else {
            activeOtps.clear();
            failedAttempts.clear();
            lockoutUntil.clear();
        }
    }

    private String cleanPhone(String phone) {
        if (phone == null || phone.isBlank()) return "919876543210";
        return phone.replaceAll("[^0-9]", "");
    }
}
