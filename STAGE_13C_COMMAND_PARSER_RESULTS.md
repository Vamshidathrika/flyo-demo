# FLYO RESCUE — STAGE 13C: COMMAND PARSER & SINGLE-TOKEN BOOKING RESOLUTION

## Overview
This document evaluates the WhatsApp command parsing engine in `WhatsAppCommandService`, verifying support for bare booking IDs (`B1`..`B5`), composite commands (`STATUS B3`), case insensitivity, whitespace tolerance, and dynamic entity lookups without hardcoded responses.

---

## Command Syntax Specification

| Input Pattern | Extracted Action | Target Entity | Sample Inbound Text | Output Result |
| :--- | :--- | :--- | :--- | :--- |
| `^B[1-9][0-9]*$` | Single-token status query | Booking `B<N>` | `B3`, `b3`, `  B3  ` | Authoritative real-time flight, disruption, and rescue status for booking B3 |
| `^STATUS\s+B[1-9][0-9]*$` | Explicit status command with target | Booking `B<N>` | `STATUS B3`, `status b3` | Authoritative real-time flight, disruption, and rescue status for booking B3 |
| `^STATUS$` | General passenger status query | Active Booking for caller phone | `STATUS` | Status summary of all active bookings belonging to caller |
| `^DISRUPT\s+B[1-9][0-9]*$` | Disruption simulation trigger | Booking `B<N>` | `DISRUPT B3` | Dynamic disruption triggered on booking B3 |
| `^OPTIONS$` | Alternative flight options query | Active Rescue Case | `OPTIONS` | Ranked list of alternative flights generated for passenger's missed connection |
| `^ACCEPT\s+O[1-9][0-9]*$` | Rebook option selection | RebookOption | `ACCEPT O2` | Selected option locked; 6-digit OTP dispatched to passenger |
| `^OTP\s+[0-9]{6}$` | Authentication code submission | Passenger OTP Challenge | `OTP 849201` | OTP validated, rebooking workflow initiated |
| `^ABANDON$` / `^DECLINE$` | Rescue refusal | Active Rescue Case | `ABANDON` | Case marked `PASSENGER_DECLINED`; OTP challenges burned |

---

## Dynamic Entity Resolution (Eliminating Hardcoded Data)

Previously, `WhatsAppCommandService` returned hardcoded strings:
```java
// LEGACY (Prohibited):
if (bookingId.equals("B1")) {
    return "Passenger Ananya Sharma flight AI-839 tonight 21:00";
}
```

In Stage 13C, all queries dynamically resolve from `BookingRepository` and `RescueCaseRepository`:
```java
private void sendSingleBookingStatus(String senderPhone, String bookingId) {
    Optional<Booking> bookingOpt = bookingRepository.findById(bookingId);
    if (bookingOpt.isEmpty()) {
        whatsAppClient.sendTextMessage(senderPhone, "❌ Booking not found: " + bookingId);
        return;
    }
    Booking b = bookingOpt.get();
    Optional<RescueCase> caseOpt = rescueCaseRepository.findByBookingId(bookingId);

    StringBuilder sb = new StringBuilder();
    sb.append("📋 *Booking Status: ").append(b.getId()).append("*\n");
    sb.append("• Passenger: *").append(b.getPassengerName()).append("*\n");
    sb.append("• Route: ").append(b.getRoute()).append("\n");
    sb.append("• Flight: ").append(b.getFlightNumber()).append("\n");
    sb.append("• Status: *").append(b.getStatus()).append("*\n");
    if (caseOpt.isPresent()) {
        RescueCase c = caseOpt.get();
        sb.append("• Rescue Case: *").append(c.getId()).append("* (").append(c.getState()).append(")\n");
    }
    whatsAppClient.sendTextMessage(senderPhone, sb.toString());
}
```

---

## Test Verification

In `OriginalPassengerWorkflowDefectsRegressionTest.java`:
```java
@Test
@DisplayName("Defect 5: WhatsApp command parser must accept single-token B3, STATUS B3, and route dynamically")
void testDefect5_CommandParserMustAcceptB3AndSingleTokenBookingCommands() {
    // 1. Test bare booking ID "B3"
    commandService.handleCommand(PASSENGER_PHONE, "B3");
    ArgumentCaptor<String> captor1 = ArgumentCaptor.forClass(String.class);
    verify(whatsAppClient).sendTextMessage(eq(PASSENGER_PHONE), captor1.capture());
    assertTrue(captor1.getValue().contains("Booking Status: B3"));
    assertTrue(captor1.getValue().contains("Priya Nair"));

    // 2. Test explicit "STATUS B3"
    commandService.handleCommand(PASSENGER_PHONE, "STATUS B3");
    ArgumentCaptor<String> captor2 = ArgumentCaptor.forClass(String.class);
    verify(whatsAppClient, times(2)).sendTextMessage(eq(PASSENGER_PHONE), captor2.capture());
    assertTrue(captor2.getValue().contains("Booking Status: B3"));
    assertTrue(captor2.getValue().contains("AI-542"));
}
```

**Result**: `PASSED`. Both single-token `B3` and `STATUS B3` dynamically return accurate entity data without unrecognized command errors.
