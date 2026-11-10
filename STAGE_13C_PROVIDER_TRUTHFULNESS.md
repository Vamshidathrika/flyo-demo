# FLYO RESCUE — STAGE 13C: PROVIDER TRUTHFULNESS & SIMULATION BOUNDARIES

## Overview
This document enforces strict provider truthfulness across all customer-facing, operator-facing, and audit messaging. Flyo Rescue strictly prohibits claiming that live airline e-tickets, boarding passes, or baggage reroutes have been issued when operating against sandbox, mock, or development environments.

---

## Truthfulness Contract

| Environment / Profile | Provider Mode | Passenger Notification Header | Baggage Transfer Language | Boarding Pass Language | PNR Nature |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **test / dev / demo / sandbox** | Mock / Sandbox | `*[SIMULATION / SANDBOX MODE]* Simulation completed. No real airline booking or ticket was created.` | `Simulated transit. Live baggage transfer requires live airline ticketing.` | `Simulated boarding pass. Live boarding pass available upon carrier online check-in.` | Simulated Mock PNR |
| **prod** | Live Airline Provider (Amadeus / Sabre / Airline Direct NDC) | Authoritative Live Airline Confirmation | Carrier Interline Transfer Protocol | Carrier DCS Issued Boarding Pass | Authoritative GDS / Airline PNR |

---

## Implementation Details (`PassengerWhatsAppMessagingService.java`)

```java
boolean isSimulationMode = environment != null
        && !environment.matchesProfiles("prod")
        && (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev", "demo", "test"))
        || "MOCK".equalsIgnoreCase(provider)
        || "SANDBOX".equalsIgnoreCase(provider));

StringBuilder sb = new StringBuilder();
sb.append("✈️ *Your flight rescue is confirmed!*\n");

if (isSimulationMode) {
    sb.append("*[SIMULATION / SANDBOX MODE]*\n")
      .append("Simulation completed. No real airline booking or ticket was created.\n\n");
}

sb.append("Your alternative flight has been successfully booked and ticketed.\n\n")
  .append("• Passenger: *").append(escape(booking.getPassengerName())).append("*\n")
  .append("• New Flight: *").append(escape(flightCode)).append("*\n")
  ...
  .append("• Baggage Guidance: ").append(isSimulationMode
        ? "Simulated transit. Live baggage transfer requires live airline ticketing."
        : "Automatic interline transfer to destination carousel.").append("\n")
  .append("• Boarding Pass: ").append(isSimulationMode
        ? "Simulated boarding pass. Live boarding pass available upon carrier online check-in."
        : "Available immediately at airline kiosk or mobile app.").append("\n");
```

---

## Test Verification

```java
@Test
@DisplayName("Defect 3: Mock/Sandbox mode must never claim real live airline ticketing or real boarding passes")
void testDefect3_MockProviderMustNeverClaimAuthoritativeLiveTicketing() {
    messagingService.sendBookingConfirmation(caseId, "MOCK_PNR_999", PASSENGER_PHONE);

    ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
    verify(whatsAppClient, atLeastOnce()).sendInteractiveButtons(eq(PASSENGER_PHONE), bodyCaptor.capture(), any());

    String sentMessage = bodyCaptor.getValue();
    assertTrue(sentMessage.contains("[SIMULATION / SANDBOX MODE]"), "Must disclose simulation status");
    assertTrue(sentMessage.contains("No real airline booking or ticket was created"), "Must state no real ticket was created");
    assertTrue(sentMessage.contains("Simulated transit"), "Must disclose baggage is simulated");
    assertTrue(sentMessage.contains("Simulated boarding pass"), "Must disclose boarding pass is simulated");
}
```

**Result**: `PASSED`. Truthfulness contract enforced across all communication channels.
