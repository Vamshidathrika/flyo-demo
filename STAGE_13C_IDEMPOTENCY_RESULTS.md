# FLYO RESCUE — STAGE 13C: IDEMPOTENCY & DUPLICATE MESSAGE SUPPRESSION

## Overview
This document evaluates the idempotency mechanisms protecting Flyo Rescue against duplicate external dispatches, double bookings, redundant webhook events, and duplicate customer notifications.

---

## The Dual-Dispatch Problem
In the previous implementation, when a passenger submitted an OTP:
1. `PassengerRescueWorkflowService.handleOtpSubmission` invoked `rebookingOrchestrationService.executeRebookingWorkflow`.
2. `executeRebookingWorkflow` reached the `DONE` state and dispatched a WhatsApp confirmation message to the passenger via `messagingService.sendBookingConfirmation(caseId, pnr, phone)`.
3. Concurrently, `handleOtpSubmission` itself constructed a confirmation response string and attempted to deliver it to the passenger.
4. Because both dispatches ran in close succession without a distributed deduplication key, passengers received duplicate notifications.

---

## Deduplication Architecture

```
[Dispatch Request 1 (RebookingOrchestrationService)]
                       │
                       ▼
    [Deduplication Key Calculation]
    dedupeKey = caseId + "_" + verifiedPnr + "_" + passengerPhone
                       │
    [sentConfirmations.add(dedupeKey)] ──> Returns TRUE (First Entry)
                       │
                       ▼
      [Dispatch to Meta WhatsApp API] (Delivered)

[Dispatch Request 2 (PassengerRescueWorkflowService)]
                       │
                       ▼
    [Deduplication Key Calculation]
    dedupeKey = caseId + "_" + verifiedPnr + "_" + passengerPhone
                       │
    [sentConfirmations.add(dedupeKey)] ──> Returns FALSE (Already exists!)
                       │
                       ▼
      [SUPPRESS DISPATCH & LOG WARNING]
      "[Passenger WhatsApp] Suppressed duplicate booking confirmation delivery"
```

---

## Code Reference (`PassengerWhatsAppMessagingService.java`)

```java
private final java.util.Set<String> sentConfirmations = ConcurrentHashMap.newKeySet();

@Transactional
public void sendBookingConfirmation(String caseId, String verifiedPnr, String passengerPhone) {
    String dedupeKey = caseId + "_" + verifiedPnr + "_" + passengerPhone;
    if (!sentConfirmations.add(dedupeKey)) {
        log.warn("[Passenger WhatsApp] Suppressed duplicate booking confirmation delivery for dedupeKey {}", dedupeKey);
        return;
    }
    // Proceed with unique dispatch
    whatsAppClient.sendInteractiveButtons(passengerPhone, messageBody, buttons);
}
```

---

## Automated Stress Testing & Concurrency Proof

In `OriginalPassengerWorkflowDefectsRegressionTest.java`:
```java
@Test
@DisplayName("Defect 4: Concurrent success dispatches must deduplicate and never double-deliver to passenger")
void testDefect4_ConcurrentSuccessDispatchesMustDeduplicateAndNeverDoubleDeliver() throws Exception {
    String pnr = "R9FL301";
    int threads = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(threads);

    for (int i = 0; i < threads; i++) {
        executor.submit(() -> {
            try {
                messagingService.sendBookingConfirmation(caseId, pnr, PASSENGER_PHONE);
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await(5, TimeUnit.SECONDS);
    executor.shutdown();

    // Verify WhatsApp client was invoked EXACTLY ONCE despite 10 concurrent threads
    verify(whatsAppClient, times(1)).sendInteractiveButtons(eq(PASSENGER_PHONE), anyString(), any());
}
```

**Result**:
- 10 concurrent racing threads dispatched to `sendBookingConfirmation`.
- Exactly 1 dispatch reached `whatsAppClient.sendInteractiveButtons`.
- Exactly 9 dispatches were safely suppressed.
- `PASSED` cleanly.
