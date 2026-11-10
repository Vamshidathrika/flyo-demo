# FLYO RESCUE — STAGE 13C: OTP LIFECYCLE & RESCUE ABANDONMENT SECURITY

## Overview
This document certifies the security posture of passenger OTP authentication, challenge lifecycles, and cryptographic invalidation upon rescue abandonment or terminal case transitions.

---

## Security Vulnerability in Previous Architecture
Previously, OTP challenges were stored in `passenger_otp_challenges` with a fixed TTL (5 minutes). When a passenger issued `ABANDON` or `DECLINE`, the case state transitioned to `PASSENGER_DECLINED`, but the active challenge remained valid in the repository until TTL expiration. If an attacker or confused passenger subsequently submitted the old OTP, `PassengerConfirmationService` evaluated the hash without verifying whether the underlying rescue case had been abandoned, resulting in invalid rebooking executions.

---

## Remediated Architecture & Lifecycle State Machine

```
[Inbound ABANDON / DECLINE]
              │
              ▼
  [PassengerRescueWorkflowService.handleAbandonRescue]
              │
              ├── 1. stateMachineService.transition(rescueCase, PASSENGER_DECLINED, ...)
              │
              └── 2. passengerOtpService.invalidateOtpsForCase(caseId, "PASSENGER_ABANDONED")
                                  │
                                  ├── Invalidate DB challenges (status = "INVALIDATED")
                                  └── Evict from ephemeral memory cache
```

```
[Inbound OTP Submission (e.g. "OTP 849201")]
              │
              ▼
  [PassengerConfirmationService.confirmAndVerify]
              │
              ├── 1. Check Case State:
              │      IF state in (PASSENGER_DECLINED, ABANDONED, CANCELLED, DONE, FAILED_ESCALATED)
              │      -> Return immediate failure: "ATTEMPT_ABANDONED"
              │      (Hash evaluation bypassed)
              │
              ├── 2. Check Active Challenges in Repository
              │      IF no challenge with status = "PENDING"
              │      -> Return failure: "CHALLENGE_EXPIRED"
              │
              ├── 3. Cryptographic Constant-Time Hash Comparison
              │      IF code matches -> Verify and burn challenge (status = "VERIFIED")
              │      ELSE -> Increment attempts; Lock out if attempts >= 3
              │
              └── 4. Proceed to Rebooking if and only if state is valid
```

---

## Code References

### `PassengerOtpService.java`
```java
@Transactional
public void invalidateOtpsForCase(String caseId, String reason) {
    List<PassengerOtpChallenge> activeChallenges = challengeRepository.findByCaseIdOrderByCreatedAtDesc(caseId);
    for (PassengerOtpChallenge c : activeChallenges) {
        if ("PENDING".equals(c.getStatus())) {
            c.setStatus("INVALIDATED");
            c.setFailedAttempts(c.getFailedAttempts() + 1);
            c.setUpdatedAt(LocalDateTime.now());
            challengeRepository.save(c);
            log.info("[OTP Security] Invalidation triggered for case {}: {}", caseId, reason);
        }
    }
    ephemeralOtpCache.remove(caseId);
}
```

### `PassengerConfirmationService.java`
```java
// Immediate state rejection prior to hash comparison
String currentState = rescueCase.getState();
if (CaseStateMachineService.STATE_PASSENGER_DECLINED.equalsIgnoreCase(currentState)
        || "ABANDONED".equalsIgnoreCase(currentState)
        || "CANCELLED".equalsIgnoreCase(currentState)
        || CaseStateMachineService.STATE_DONE.equalsIgnoreCase(currentState)
        || CaseStateMachineService.STATE_FAILED_ESCALATED.equalsIgnoreCase(currentState)
        || CaseStateMachineService.STATE_BOOKING_FAILED.equalsIgnoreCase(currentState)) {
    log.warn("[Passenger Confirmation] Rejected OTP verification for case {} in terminal/abandoned state {}",
            caseId, currentState);
    return new PassengerOtpResult(false, "ATTEMPT_ABANDONED",
            "This rescue attempt has been cancelled or abandoned. Old OTPs cannot be used to resume the case.");
}
```

---

## Test Verification

```java
@Test
@DisplayName("Defect 2: Abandoned rescue followed by old OTP submission must be strictly rejected")
void testDefect2_AbandonedRescueFollowedByOldOtp_MustBeRejected() {
    // Generate valid OTP
    String code = otpService.generateAndSendOtp(caseId, PASSENGER_PHONE);
    
    // Passenger abandons
    workflowService.handleAbandonRescue(caseId, PASSENGER_PHONE);
    
    // Attempt verification with previously valid code
    PassengerOtpResult result = confirmationService.confirmAndVerify(caseId, PASSENGER_PHONE, code);
    
    assertFalse(result.isSuccess(), "Old OTP submission must fail on abandoned rescue");
    assertEquals("ATTEMPT_ABANDONED", result.getErrorCode());
}
```

**Result**: `PASSED`. Old OTP replay on abandoned cases is strictly blocked.
