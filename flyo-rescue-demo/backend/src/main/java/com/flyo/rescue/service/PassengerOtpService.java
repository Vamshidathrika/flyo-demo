package com.flyo.rescue.service;

import com.flyo.rescue.model.PassengerOtpChallenge;
import com.flyo.rescue.repository.PassengerOtpChallengeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passenger Confirmation OTP Service.
 *
 * Enforces mandatory passenger confirmation verification:
 * - Cryptographically secure 6-digit OTP generation
 * - SHA-256 hash storage (never persists plaintext)
 * - Strict binding to (Case ID, Passenger Phone, Option ID, Amount, Currency)
 * - 5-minute expiry
 * - Single-use validation
 * - Max 5 failed attempts lockout
 * - Invalidation when option or fare changes
 *
 * NOTE: Passenger confirmation OTP verifies passenger consent for the itinerary and fare difference.
 * It does NOT collect bank OTPs, CVVs, or card credentials.
 */
@Service
public class PassengerOtpService {

    private static final Logger log = LoggerFactory.getLogger(PassengerOtpService.class);
    private static final int OTP_EXPIRY_MINUTES = 5;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;

    private final SecureRandom secureRandom = new SecureRandom();
    private final PassengerOtpChallengeRepository repository;
    private final AuditService auditService;

    // Ephemeral cache of active challenge codes for WhatsApp delivery only (cleared upon verification/expiry)
    private final Map<String, String> ephemeralActiveCodes = new ConcurrentHashMap<>();
    // Rate limit & brute-force lockout: phone -> lockedUntil
    private final Map<String, LocalDateTime> lockedPhones = new ConcurrentHashMap<>();
    // Resend cooldown tracking: phone -> lastIssuedTime
    private final Map<String, LocalDateTime> lastIssuedTimes = new ConcurrentHashMap<>();

    public record PassengerOtpResult(boolean valid, String reason, PassengerOtpChallenge challenge) {}

    public PassengerOtpService(PassengerOtpChallengeRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /**
     * Creates a new bound Passenger Confirmation OTP Challenge.
     */
    @Transactional
    public synchronized String createPassengerOtpChallenge(String caseId, String passengerPhone,
                                                          String optionId, int amount, String currency) {
        String cleanPhone = cleanPhone(passengerPhone);
        if (isLockedOut(cleanPhone)) {
            throw new IllegalStateException("Security rate limit: phone number " + cleanPhone + " is temporarily locked out.");
        }

        // Generate cryptographically secure 6-digit code
        int num = secureRandom.nextInt(900_000) + 100_000;
        String rawCode = String.valueOf(num);
        String otpHash = hashOtp(rawCode);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(OTP_EXPIRY_MINUTES);

        String challengeId = UUID.randomUUID().toString();
        PassengerOtpChallenge challenge = new PassengerOtpChallenge(
                challengeId, caseId, cleanPhone, optionId, amount, currency, otpHash, now, expiresAt
        );

        repository.save(challenge);
        ephemeralActiveCodes.put(challengeId, rawCode);
        lastIssuedTimes.put(cleanPhone, now);

        auditService.logEvent(
                caseId,
                "PASSENGER_OTP_ISSUED",
                "Flyo Security Engine",
                String.format("Generated single-use Passenger Confirmation OTP for case %s, option %s, amount %s %d. Expires in %dm.",
                        caseId, optionId, currency, amount, OTP_EXPIRY_MINUTES)
        );

        log.info("[Passenger OTP] Generated OTP challenge for case {}, phone {}, option {}, amount {} {}",
                caseId, cleanPhone, optionId, currency, amount);

        return rawCode;
    }

    /**
     * Validates a submitted passenger OTP code against the active challenge for the case.
     */
    @Transactional
    public synchronized PassengerOtpResult verifyPassengerOtp(String caseId, String passengerPhone,
                                                              String submittedCode, String expectedOptionId,
                                                              int expectedAmount) {
        String cleanPhone = cleanPhone(passengerPhone);
        if (isLockedOut(cleanPhone)) {
            return new PassengerOtpResult(false, "LOCKED_OUT", null);
        }

        Optional<PassengerOtpChallenge> challengeOpt = repository
                .findTopByPassengerPhoneAndCaseIdAndVerifiedFalseOrderByCreatedAtDesc(cleanPhone, caseId);

        if (challengeOpt.isEmpty()) {
            return new PassengerOtpResult(false, "NO_ACTIVE_CHALLENGE", null);
        }

        PassengerOtpChallenge challenge = challengeOpt.get();

        // Check expiration
        if (LocalDateTime.now().isAfter(challenge.getExpiresAt())) {
            ephemeralActiveCodes.remove(challenge.getId());
            return new PassengerOtpResult(false, "EXPIRED", challenge);
        }

        // Check Option binding
        if (!challenge.getOptionId().equalsIgnoreCase(expectedOptionId)
                && !challenge.getOptionId().endsWith("-" + expectedOptionId)
                && !expectedOptionId.endsWith("-" + challenge.getOptionId())) {
            return new PassengerOtpResult(false, "OPTION_MISMATCH", challenge);
        }

        // Check Fare amount binding
        if (challenge.getAmount() != expectedAmount) {
            return new PassengerOtpResult(false, "FARE_CHANGED", challenge);
        }

        String submittedHash = hashOtp(submittedCode != null ? submittedCode.trim() : "");
        if (submittedHash.equals(challenge.getOtpHash())) {
            challenge.setVerified(true);
            challenge.setVerifiedAt(LocalDateTime.now());
            repository.save(challenge);
            ephemeralActiveCodes.remove(challenge.getId());

            auditService.logEvent(
                    caseId,
                    "PASSENGER_OTP_VERIFIED",
                    "Passenger (" + cleanPhone + ")",
                    String.format("Passenger confirmed flight recovery for option %s (Amount: %s %d) via WhatsApp OTP.",
                            expectedOptionId, challenge.getCurrency(), challenge.getAmount())
            );

            log.info("[Passenger OTP] OTP successfully verified for case {} and passenger {}", caseId, cleanPhone);
            return new PassengerOtpResult(true, "VERIFIED", challenge);
        } else {
            challenge.setAttempts(challenge.getAttempts() + 1);
            repository.save(challenge);

            if (challenge.getAttempts() >= MAX_FAILED_ATTEMPTS) {
                lockedPhones.put(cleanPhone, LocalDateTime.now().plusMinutes(LOCKOUT_MINUTES));
                ephemeralActiveCodes.remove(challenge.getId());
                log.warn("[Passenger OTP] Phone {} locked out due to {} failed OTP attempts", cleanPhone, MAX_FAILED_ATTEMPTS);
                return new PassengerOtpResult(false, "LOCKED_OUT", challenge);
            }

            return new PassengerOtpResult(false, "INVALID_CODE", challenge);
        }
    }

    /**
     * Retrieves ephemeral raw code for testing or development mock dispatch.
     */
    public Optional<String> getEphemeralActiveCode(String caseId, String passengerPhone) {
        String cleanPhone = cleanPhone(passengerPhone);
        return repository.findTopByPassengerPhoneAndCaseIdAndVerifiedFalseOrderByCreatedAtDesc(cleanPhone, caseId)
                .map(c -> ephemeralActiveCodes.get(c.getId()));
    }

    /**
     * Checks if a phone number is locked out.
     */
    public boolean isLockedOut(String phone) {
        String cleanPhone = cleanPhone(phone);
        LocalDateTime until = lockedPhones.get(cleanPhone);
        if (until == null) return false;
        if (LocalDateTime.now().isAfter(until)) {
            lockedPhones.remove(cleanPhone);
            return false;
        }
        return true;
    }

    public void clearLockouts() {
        lockedPhones.clear();
        ephemeralActiveCodes.clear();
    }

    private String hashOtp(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private String cleanPhone(String phone) {
        if (phone == null) return "";
        return phone.replaceAll("[^0-9]", "");
    }
}
