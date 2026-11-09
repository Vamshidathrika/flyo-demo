# STAGE 10: AIRLINE ORDER LIFECYCLE & FOUR-STATE DISRUPTION TAXONOMY
**Flyo Disruption Management Platform**  
**Engineering Specification:** Airline Reservation, Booking & Ticketing States

---

## 1. Distinction of Four Critical Business States

In aviation distribution, confusing a flight offer with a confirmed booking or an issued ticket is a catastrophic financial and legal error. Flyo strictly separates and enforces four distinct business states:

```
┌─────────────────────────┐
│  1. FLIGHT OFFER        │ ➔ Flight, schedule & fare retrieved from GDS shopping API.
│     (Pre-Reservation)   │   No inventory reserved; price is subject to dynamic change.
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  2. ORDER CREATED       │ ➔ Order submitted to Amadeus NDC; temporary seat inventory hold
│     (Reservation Held)  │   established. Provider returns internal order reference ID.
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  3. PNR CONFIRMED       │ ➔ Operating carrier host validates inventory and assigns
│     (Record Locator)    │   official 6-character PNR (e.g. R7X2K9). Reservation confirmed.
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  4. TICKET ISSUED       │ ➔ Electronic ticket document (13 digits, e.g. 098-2451928410)
│     (E-Ticket / Coupon) │   issued via BSP/ARC. Flight coupons set to OPEN_FOR_USE.
└─────────────────────────┘
```

---

## 2. State-by-State Verification Matrix

| State | Provider Artifact | Financial Obligation | Cancellation / Void Window | Flyo State Machine Equivalent |
| :--- | :--- | :--- | :--- | :--- |
| **FLIGHT_OFFER** | Offer ID / JSON payload | ₹0 (No commitment) | Instant discard | `OPTIONS_READY` |
| **ORDER_CREATED** | Provider Order ID (`ORD-...`) | Pre-authorization hold on VCC | Host timeout (typically 15-30m) | `APPROVED` / `REBOOKING` |
| **PNR_CONFIRMED** | 6-char GDS PNR (`R7X2K9`) | Authorized VCC balance | Carrier void window (same-day) | `REBOOKING` |
| **TICKET_ISSUED** | 13-digit E-Ticket (`098-...`)| Full settlement capture | ARC/BSP refund rules apply | `DONE` |

---

## 3. Database Schema Persistence (`airline_order_records`)

The `airline_order_records` table (Flyway Migration `V10`) durably tracks every state transition:

```sql
CREATE TABLE IF NOT EXISTS airline_order_records (
    id VARCHAR(64) PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'tenant_default',
    flight_number VARCHAR(32) NOT NULL,
    carrier_code VARCHAR(8) NOT NULL,
    passenger_name VARCHAR(128) NOT NULL,
    order_lifecycle_state VARCHAR(32) NOT NULL,
    provider_order_id VARCHAR(128),
    gds_pnr VARCHAR(16),
    ticket_number VARCHAR(64),
    ticketing_status VARCHAR(32),
    coupon_status VARCHAR(32),
    amount INT NOT NULL DEFAULT 0,
    currency VARCHAR(8) NOT NULL DEFAULT 'INR',
    correlation_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0
);
```

---

## 4. Failure Mode Handling Across Lifecycle States

1. **Failure during FLIGHT_OFFER**: Re-query alternative inventory without financial impact.
2. **Failure during ORDER_CREATED**: Release pre-authorization hold on Virtual Card; no void fee.
3. **Failure during PNR_CONFIRMED (before Ticketing)**: Execute Amadeus `OrderCancel` to release seat inventory; void VCC auth.
4. **Failure after TICKET_ISSUED**: Requires electronic ticket void (`TK VOID`) within same calendar day or formal IATA BSP refund request.
