# STAGE 13B: Passenger Communication Reliability & Omnichannel Drawer Verification

**System:** Flyo Rescue Agent — Airline OCC Operations Console  
**Milestone:** Stage 13B Runtime Certification & Operational Usability  
**Status:** PASS & CERTIFIED  

---

## 1. Contextual Communication Architecture

In Stage 13B, the dedicated passenger messaging tab was replaced with an off-canvas, contextual slide-over drawer (`#operator-drawer`). This drawer is directly accessible from active incidents without displacing flight manifest tables or operational dashboards.

```
Main Operations Viewport (Full 100% Width)
┌────────────────────────────────────────────────────────┐ ┌──────────────────────┐
│ Trip Monitor / Incident Recovery / Operations Control  │ │ Contextual Drawer    │
│                                                        │ │ (Slide-Over Panel)   │
│ - Dense 11-column Manifest                             │ │                      │
│ - Ranked Alternative Flights                           │ │ - Passenger WhatsApp │
│ - 7-Stage State Stepper                                │ │ - Proof of Delivery  │
│ - Circuit Breakers                                     │ │ - 2FA Verification   │
└────────────────────────────────────────────────────────┘ └──────────────────────┘
```

---

## 2. Delivery State Tracking & Webhook Lifecycle

The communication subsystem tracks discrete message delivery milestones received from the Meta WhatsApp Cloud API adapter:

| Delivery State | State Badge & Styling | Technical Condition | Authoritative Proof |
| :--- | :--- | :--- | :--- |
| **`QUEUED`** | Gray Badge (`#64748b`) | Message staged in outbox; pending carrier transmission | Outbox record created with UUID |
| **`SENT`** | Blue Badge (`#0284c7`) | Accepted by Meta WhatsApp Cloud API / gateway | Provider Message ID (`wamid.HB...`) assigned |
| **`DELIVERED`** | Cyan Badge (`#0891b2`) | Confirmed arrival on passenger handset | Inbound webhook receipt with status `delivered` |
| **`READ`** | Green Badge (`#16a34a`) | Passenger opened conversation thread | Inbound webhook receipt with status `read` |
| **`REPLIED`** | Purple Badge (`#9333ea`) | Inbound text or interactive button response received | Inbound message event processed by command bot |
| **`FAILED`** | Red Badge (`#dc2626`) | Delivery failure (invalid number, carrier bounce) | Webhook error payload (`error_code: 131026`) |
| **`UNKNOWN`** | Amber Badge (`#d97706`)| Network timeout or unacknowledged webhook state | Telemetry watchdog alerts operator desk |

---

## 3. Resilience to Duplicate & Out-of-Order Webhook Events

1. **Idempotent Webhook Deduplication:**  
   Every incoming webhook is evaluated against unique idempotency keys (`evt_idem_<hash>`). Duplicate delivery of `DELIVERED` or `READ` events results in an immediate cache hit without duplicating chat bubbles or outbox events (`WebhookEventProcessor` test confirmed).
2. **Out-of-Order Rejection:**  
   If a `READ` event arrives before a delayed `DELIVERED` notification, the message state machine respects monotonically increasing state priority (`QUEUED` $\to$ `SENT` $\to$ `DELIVERED` $\to$ `READ`). A stale `DELIVERED` receipt cannot downgrade an already `READ` message.

---

## 4. Privacy, RBAC & Honest Telecommunications Demarcation

- **RBAC Protection:** Passenger phone numbers, PII, and raw message transcripts require authenticated sessions with `ROLE_OPERATOR` or `ROLE_ADMIN`. Unauthenticated requests receive HTTP 401.
- **Strict Demarcation of Simulated Comms:**  
  When operating in sandbox mode, all outbound messages and cards bear an explicit header:  
  `[SANDBOX SIMULATION · TEST MODE]`  
  Simulated delivery confirmations are explicitly labeled as mock receipts so dispatchers never mistake test simulations for real carrier-delivered legal notices.
