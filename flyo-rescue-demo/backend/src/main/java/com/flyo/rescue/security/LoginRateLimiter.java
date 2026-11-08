package com.flyo.rescue.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory rate limiter for login authentication attempts.
 *
 * Enforces OWASP API Security Top 10 (API2:2023 Broken Authentication & Brute Force Protection):
 * - Maximum 5 failed attempts per client key within a 5-minute sliding window.
 * - Temporary 15-minute lockout upon exceeding the threshold.
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_SECONDS = 900; // 15 minutes
    private static final long ATTEMPT_WINDOW_SECONDS = 300;   // 5 minutes

    private final Map<String, AttemptTracker> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) return false;
        AttemptTracker tracker = attempts.get(clientKey);
        if (tracker == null) return false;

        Instant now = Instant.now();
        if (tracker.lockedUntil != null) {
            if (now.isBefore(tracker.lockedUntil)) {
                return true;
            } else {
                // Lockout expired: reset tracker
                attempts.remove(clientKey);
                return false;
            }
        }
        return false;
    }

    public void recordSuccess(String clientKey) {
        if (clientKey != null) {
            attempts.remove(clientKey);
        }
    }

    public void recordFailure(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) return;

        Instant now = Instant.now();
        attempts.compute(clientKey, (key, tracker) -> {
            if (tracker == null || now.isAfter(tracker.firstAttemptAt.plusSeconds(ATTEMPT_WINDOW_SECONDS))) {
                return new AttemptTracker(1, now, null);
            }

            int newCount = tracker.failedCount + 1;
            Instant lockedUntil = null;
            if (newCount >= MAX_FAILED_ATTEMPTS) {
                lockedUntil = now.plusSeconds(LOCKOUT_DURATION_SECONDS);
                log.warn("[Security] Rate limiter triggered lockout for client '{}' until {}", clientKey, lockedUntil);
            }
            return new AttemptTracker(newCount, tracker.firstAttemptAt, lockedUntil);
        });
    }

    public void resetForTests() {
        attempts.clear();
    }

    private static class AttemptTracker {
        final int failedCount;
        final Instant firstAttemptAt;
        final Instant lockedUntil;

        AttemptTracker(int failedCount, Instant firstAttemptAt, Instant lockedUntil) {
            this.failedCount = failedCount;
            this.firstAttemptAt = firstAttemptAt;
            this.lockedUntil = lockedUntil;
        }
    }
}
