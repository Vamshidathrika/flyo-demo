# FLYO RESCUE — SIMULATION & PRODUCTION ISOLATION ARCHITECTURE

## 1. Overview & Policy Rationale
In enterprise aviation operations, confusing a simulated action with a live GDS transaction or airline host ticket can result in severe financial loss, booking collisions, and passenger stranding.

Flyo Rescue enforces strict cryptographic, network, and UI boundaries between **Production (`prod`)**, **Staging/UAT (`staging`)**, and **Demo/Testing (`demo`, `test`)** environments.

---

## 2. Environment Profile Boundary Rules

| Dimension | Production Profile (`prod`) | Staging / Partner UAT (`staging`) | Demonstration / Test Profile (`demo`, `test`) |
| :--- | :--- | :--- | :--- |
| **Data Seeder** | **HARD BLOCKED**. `FlyoDataSeeder` is disabled via `@Profile("!prod")` and runtime assertion. | **BLOCKED**. Relies on tenant data imports. | **ENABLED** if database is empty or explicitly seeded for automated tests. |
| **Airline Ticketing** | Real GDS / NDC Host Connection (Requires bilateral IATA accreditation). Currently disabled pending contract. | Amadeus Enterprise GDS Test Environment (Sandbox credentials). | Playwright Mock Browser Automation / Synthetic GDS Stub. |
| **Payment Settlement** | Live PCI-DSS Level 1 Processor (Stripe Issuing / Virtual Mastercard API). | Stripe Test Mode / Sandbox Virtual Card Account. | `SandboxPaymentAdapter` returning deterministic tokenized transactions. |
| **Ticket Issuance Label** | Authoritative 13-digit IATA e-ticket coupon. | Marked `[SANDBOX TICKET - NOT VALID FOR TRAVEL]`. | Explicitly prefixed with `[SIMULATION TICKET - NOT A REAL BOARDING PASS]`. |
| **VCC Presentation** | Real masked card `•••• •••• •••• 1234` with audited live auth. | Tokenized test VCC. | Suffix clearly stamped with `[SIMULATED VCC]` badge. No fake card numbers disguised as real. |
| **WhatsApp Notifications** | Production Meta Cloud API phone number with verified Meta Business Manager. | WhatsApp Sandbox / Twilio Test Account. | In-memory message bus & simulated passenger terminal. |

---

## 3. Implementation of Safety Controls

### 3.1. Zero Seed Execution in Production
The database seeder component enforces a strict profile check:
```java
@Component
@Profile("!prod")
public class FlyoDataSeeder implements CommandLineRunner {
    @Override
    public void run(String... args) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("FATAL: FlyoDataSeeder cannot run under the production profile!");
        }
        // Seeding logic strictly guarded
    }
}
```
In `prod`, the database boots with 0 records. If no tenant PNRs have been ingested, the system renders an empty state instead of populating mock flights or mock passengers.

### 3.2. Simulation Watermarking & Disclosure
Every passenger-facing and operator-facing message generated in a non-production profile contains a mandatory legal watermark:
1. **WhatsApp Messages**:
   ```
   ⚠️ [SIMULATION / SANDBOX MODE]
   This is a synthetic rebooking notification generated for operational testing.
   No live airline ticket has been issued.
   ```
2. **OCC Console Incident Recovery**:
   When viewing cases executed via sandbox providers, the ticket drawer renders a persistent yellow/amber warning badge:
   `[SIMULATED TICKET — SANDBOX AMADEUS TEST ENGINE]`

### 3.3. Payment Tokenization & VCC Containment
- In `prod`, the Virtual Credit Card service calls the live banking gateway over mutual TLS (mTLS). Raw PANs are never logged, stored, or transmitted to the browser.
- In `test` and `demo` profiles, `SandboxPaymentAdapter` generates synthetic authorization tokens (e.g. `TXN-8ABC4528`) without hardcoding card suffixes or mocking fake PAN numbers.

### 3.4. Dual-Persona WhatsApp Access Control
To prevent unauthorized users from issuing rescue commands or accessing flight manifests:
- Senders with unknown phone numbers sending admin commands (e.g., `STATUS`, `DISRUPT`) receive an immediate `*Access Denied: Unrecognized operator or passenger credential.*` response.
- Legitimate passengers whose phone numbers exist in active bookings receive the concierge greeting (`✈️ Flyo · Priority Passenger Concierge`).
- Operational commands (`DISRUPT`, `APPROVE`) are restricted to verified operator accounts authenticated via session JWT or signed webhook tokens.

---

## 4. Verification Evidence
- `Stage9BEmptyProductionDatabaseTest.java`: Confirms 0 records seeded in `prod`.
- `Stage10ProductionCertificationTest.java`: Confirms clean boot against empty PostgreSQL database with isolated tenant boundaries.
- `OriginalPassengerWorkflowDefectsRegressionTest.java`: Confirms `testSimulationTicketCarriesExplicitWarning()` passes 100% cleanly.
