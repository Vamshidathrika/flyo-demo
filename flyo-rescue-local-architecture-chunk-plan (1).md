# Rescue Agent — Local End-to-End Plan (for the coding agent)

Paste this into Antigravity, in Agent mode, in an empty folder named `flyo-rescue-demo`.
Read the whole plan first. Then build ONLY Chunk 1, stop, and show its done-test.
Do not build all chunks at once. We are building in chunks, one chunk at a time.
Wait for the user to say "next chunk" before starting the next chunk.

---

## 1. WHAT WE ARE BUILDING, IN ONE SENTENCE

Rescue Agent: a disruption-recovery agent for a small travel agency.
When a flight is cancelled, it detects the broken trip, ranks rebooking options,
waits for human approval, rebooks on a mock airline portal, notifies the traveller,
and logs every step.

Hero story: Ananya Sharma, HYD → Delhi tonight, then Delhi → Toronto tomorrow.
Her HYD → Delhi flight is cancelled. The agent must save her Toronto connection.

User of this system: Ravi, the travel agency owner. NOT the traveller.
The traveller only receives a message at the end.

## 2. LOCAL-ONLY RULE — THIS IS THE FIRST PLAN, NOT PRODUCTION

Everything runs on this one machine, end to end. Nothing leaves localhost.

- Backend + dashboard + mock airline portal: http://localhost:8080
- Database: H2 in-memory, inside the backend. No Postgres, no Docker, no cloud database.
- Browser worker: local Node + Playwright, started by the backend as a local subprocess.
- Flight cancellation source: LOCAL MOCK FEED ONLY. In this plan there is no real
  airline API, no GDS (Amadeus/Sabre), no FlightAware/Cirium, no internet flight data.
  In production those would replace the mock feed. In this local build, do not call them.
- WhatsApp: SIMULATED ONLY. Show the message on screen, label it "SIMULATED — no real message sent".
  No WhatsApp Business API, no Twilio, no real phone numbers.
- Payment: NONE. Show extra cost as text only. No payment gateway, no real money.
- Login/auth: NONE for this demo.
- API keys: NONE. No keys, no secrets, no real customer data. All bookings are fictional seed data.
- LLM: NONE in this MVP. Code decides ranking, approval and safety. No LLM API calls.

If a step needs something outside localhost, stop — that step is out of scope for this local build.

## 3. ARCHITECTURE — HOW THE PIECES FIT

Think of it as 3 layers, all local:

```
Ravi (agency owner, in browser)
        |
        v
[ Dashboard ]  — plain HTML/CSS/JS, served by Spring Boot
        |  REST calls, same machine, http://localhost:8080/api/...
        v
[ Spring Boot backend — Java 17 ]
  - BookingService    : seeded bookings, list bookings
  - CaseService       : case state machine, impact calculation
  - RankingEngine     : deterministic ranking, Java code only
  - ApprovalGate      : approve / reject, blocks rebook before approval
  - AuditService      : append-only log, every event with timestamp
  - MockFeed          : local "flight cancelled" source (simulate button)
  - MockAirlinePortal : /mock-airline/search, /select, /confirm pages
  - RebookService     : calls the Playwright worker, reads its result
        |  H2 in-memory database (bookings, cases, options, audit, messages)
        |
        v  (only after approval, via local subprocess, JSON in / JSON out, max 60s)
[ Playwright worker — Node + TypeScript, in /agent ]
  - Opens the LOCAL mock portal in Chromium
  - Selects the approved option, confirms, reads the new PNR FROM THE PAGE
  - Writes result JSON + screenshots to local files: agent/screenshots/
  - Never invents a PNR, never invents a price
```

Data flow, end to end, in order:
Dashboard -> API -> CaseService -> RankingEngine -> ApprovalGate -> RebookService
-> Playwright worker -> Mock Portal -> result JSON -> Audit + WhatsApp panel -> Dashboard

No component talks to the internet. No component talks to another server.
The backend is the only server. The dashboard and the mock portal are both served by it.

## 4. DATA — KEEP IT SMALL AND EXACT

Booking: id, passengerName, pnr, route, flight, date/time, onwardFlight, hotelNote, agency, status
- B1 (hero): Ananya Sharma, PNR HYD123, HYD → DEL tonight 21:00, then DEL → Toronto (YYZ)
  tomorrow 09:30, Delhi hotel non-refundable, agency Sri Sai Travels, Hyderabad, status Checked-in
- B2: Rohit Verma, HYD → Bengaluru, on time
- B3: Priya Nair, HYD → Chennai, on time
- B4: Arjun Reddy, HYD → Mumbai, checked-in
- B5: Kavya Singh, Delhi → Hyderabad, on time

Case: id (C1 for hero), bookingId, state, cancelledSegment, impact, approvedOptionId, newPnr, error
Option: id, flight, route, departure, arrival, connectionMinutes, extraCost, savesConnection, reason, rank
AuditEvent: caseId, timestamp, event, detail
Message: caseId, type (DRAFT / SENT_SIMULATED), text

State machine — a case can only move like this, never jump:
DETECTED -> OPTIONS_READY -> AWAITING_APPROVAL -> REBOOKING -> DONE
or AWAITING_APPROVAL -> FAILED_ESCALATED (on failure) / ESCALATED (on reject)
It can never go DETECTED -> DONE. It can never rebook without approval.

Ranking rules — in code, in this order:
1. Disqualify from Rank 1 any option with under 120 minutes to the onward flight.
2. Among safe options, earliest arrival at final destination wins, then lowest extra cost.
3. Always show extra cost, arrival time, and connection time. Never hide a cost.
Expected hero ranking: O2 Rank 1, O3 Rank 2, O1 Rank 3.
- O2: FL301 via Mumbai tonight 23:30, arrives Delhi 01:40, saves YYZ, +₹4,200
- O3: FL401 direct tomorrow 10:00, +₹1,500, loses a day + hotel night
- O1: FL201 tomorrow 06:00, arrives 08:05, ₹0, only 1h25 to YYZ, does NOT save connection

Idempotency: key = caseId + optionId. Re-approving or rebooking the same case+option
returns the same result and same PNR. Never a second booking, never a second PNR.

Failure rule: retry up to 3 times, then FAILED_ESCALATED, NO new PNR, show screenshot
and manual next step ("call airline, traveller still has time before airport").
Never fake a success, never invent a PNR, never hide an error.

## 5. APIs — BUILD THESE, IN CHUNK ORDER

- GET /api/bookings
- POST /api/cases/simulate?bookingId=B1&type=CANCELLED
- GET /api/cases/{id}
- GET /api/cases/{id}/options
- POST /api/cases/{id}/approve { optionId }
- POST /api/cases/{id}/rebook  (403 "approval required" before approval, idempotent after)
- GET /api/cases/{id}/audit
- GET /api/cases/{id}/messages
- GET /mock-airline/search, /mock-airline/select, /mock-airline/confirm

## 6. FOLDER STRUCTURE

```
flyo-rescue-demo/
  backend/   (Spring Boot app, serves API + static dashboard + mock portal)
  agent/     (Playwright TypeScript worker, package.json, rebook.ts, screenshots/)
  README.md  (how to run locally, demo script, why code not LLM decides money)
```

Tech stack — do not change this in the local build:
- Backend: Java 17, Spring Boot 3, H2 in-memory, seeded data on startup
- Frontend: one static page from src/main/resources/static, plain HTML/CSS/JS, no React build
- Worker: Node.js + Playwright + TypeScript in /agent, role/text locators only (getByRole, getByText),
  NOT brittle CSS selectors

## 7. BUILD IN CHUNKS — DO ONE, TEST IT, STOP, WAIT

Rule for every chunk: build only that chunk. Run its done-test. Show PASS or FAIL honestly.
If it fails, fix it before moving on. Do not start the next chunk on your own.

Chunk 1: Setup + bookings dashboard
  Build: Spring Boot skeleton + H2 + 5 seeded bookings + dashboard at http://localhost:8080.
  Done when: dashboard lists all 5 bookings, Ananya HYD123 on top, GET /api/bookings works.

Chunk 2: Simulate cancellation + impact
  Build: "Simulate cancellation" button on B1 -> Case C1, state DETECTED, impact computed by code.
  Done when: C1 shows cancelled HYD → DEL, Toronto 09:30 missed, Delhi hotel night at risk.

Chunk 3: Ranking engine
  Build: Java ranking code only, with the rules in section 4.
  Done when: options show O2, O3, O1 in that order, with cost, times, connection safety, reason.

Chunk 4: Approval gate
  Build: Approve / Reject buttons, state AWAITING_APPROVAL.
  Done when: rebook before approval returns 403, Reject sets ESCALATED and books nothing.

Chunk 5: Mock airline portal
  Build: /mock-airline/search, /select, /confirm, same 3 options, confirm generates the new PNR.
  Done when: selecting O2 and confirming shows new PNR R7X2K9 on the mock page itself.

Chunk 6: Playwright rebook + idempotency
  Build: Playwright worker, backend subprocess call, screenshots to agent/screenshots/case-C1-step-N.png,
  result JSON with success, new PNR or error, steps, retry count, screenshot paths.
  Done when: approve O2 -> rebook -> R7X2K9 read from the page, rebook again -> same PNR, no duplicate.

Chunk 7: WhatsApp (simulated) + audit trail
  Build: simulated WhatsApp panel + audit panel with timestamps for every event.
  Done when: draft shows after options, "sent (simulated)" message with R7X2K9 shows after rebook,
  audit lists disruption, ranking, approval by "Ravi (agency owner)", rebooking steps, message sent.

Chunk 8: Failure mode + README + demo script
  Build: "Break mock portal" toggle + README (local run commands, 2-minute demo script,
  why code not LLM decides money/approval).
  Done when: with break ON, worker retries 3 times, case goes FAILED_ESCALATED, no PNR,
  screenshot + manual next step shown, no fake success.

Chunk 9 — SCOPE TO EXPLAIN TO FLYO, NOT TO BUILD IN THE LOCAL MVP:
WhatsApp control (Phase 2, after Chunks 1-8 pass)
  Explain, do not build live in local: Ravi can approve/reject from WhatsApp, not only the dashboard.
  Flow to explain: cancellation -> WhatsApp to Ravi's registered number with case + options + costs
  and buttons [Approve O2] [Approve O3] [Reject] -> webhook back to backend -> backend checks
  registered number + case is AWAITING_APPROVAL + idempotency (caseId+optionId) -> triggers rebook
  -> WhatsApp confirmation with new PNR to Ravi and to the traveller, audit log records "approved via WhatsApp".
  Safety to explain: only the registered agency number can approve, cost is shown in the message,
  buttons (not free text) select the exact option, traveller does NOT approve paid changes.
  What it needs in real life (not in local): WhatsApp Business API or Twilio WhatsApp, approved
  template for the first message, public webhook URL (breaks local-only), 24-hour reply window.
  Dashboard remains for full audit, screenshots and failure details. WhatsApp is the remote control,
  dashboard is the full view. In the local MVP, Chunk 7's simulated WhatsApp panel is the demo of this idea.

## 8. FINAL ACCEPTANCE — ONLY AFTER CHUNK 8

Run all of these, in order, and report each as PASS or FAIL with the command used:
1. Backend starts with one command, dashboard loads at http://localhost:8080
2. Simulate on B1 creates C1 with correct impact
3. Options ranked O2, O3, O1 with cost, times, reasons
4. Rebook before approval returns 403
5. Approve O2, rebook, returns R7X2K9 from the mock page, screenshots saved
6. Rebook again returns same PNR, no duplicate
7. WhatsApp panel shows simulated draft, then simulated sent message with new PNR
8. Audit trail shows every step with timestamps
9. Break Portal ON: 3 retries, FAILED_ESCALATED, no PNR, screenshot + manual next step
10. Reject path: no rebooking happens

Do not claim DONE if any item fails. Fix it, re-run, and report again.

## 9. WHAT NOT TO BUILD IN THIS LOCAL PLAN

No quote agent, no check-in agent, no itinerary generator, no chatbot page.
No login, signup, payment gateway, real WhatsApp, real airline integration, multi-user roles.
No fancy animations, no dark-mode toggle, no extra dashboards, no invented metrics.
Clean, readable, fast, local only.
