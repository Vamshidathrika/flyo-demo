# Flyo Governed AI Agent Architecture & Safety Boundaries Plan

**Document Status:** Approved Engineering Architecture Plan  
**Role:** Principal AI Systems Architect & Aviation Safety Engineer  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Architectural Philosophy: Deterministic Core with Bounded AI

The AI Agent audit confirmed that Flyo’s current codebase consists of **deterministic Java domain logic**, not autonomous LLM agents. 

> [!IMPORTANT]
> **Aviation Operations Reality:** In commercial aviation, passenger safety, airport minimum connection times, regulatory fines (DGCA CAR Part IV), and ticket re-issuance are governed by strict mathematical and legal constraints. **An autonomous LLM must never be given unrestricted agency to hallucinate ticket exchanges, bypass minimum connection times, or execute unapproved credit card payments.**

Flyo adopts a **Dual-Plane Architecture**:
1. **The Deterministic Safety Plane (Java / PostgreSQL):** Authoritative control over state transitions, MCT calculation, multi-source evidence scoring, idempotency, operator approval gates, and GDS/PSS command dispatch.
2. **The Bounded Cognitive Plane (Governed AI Agents):** Bounded language models deployed strictly for operational summarization, ambiguous itinerary analysis, operator explanation, and multilingual passenger assistance.

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                              DUAL-PLANE AGENT TOPOLOGY                                 │
 │                                                                                        │
 │  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
 │  │                         COGNITIVE PLANE (BOUNDED AI AGENTS)                      │  │
 │  │                                                                                  │  │
 │  │  ┌───────────────────────┐ ┌──────────────────────┐ ┌─────────────────────────┐ │  │
 │  │  │ Evidence Summarizer   │ │ Itinerary Explainer  │ │ Multilingual Passenger  │ │  │
 │  │  │ (NOTAM / METAR Brief) │ │ (Trade-off Assistant)│ │ WhatsApp Assistant      │ │  │
 │  │  └──────────┬────────────┘ └──────────┬───────────┘ └────────────┬────────────┘ │  │
 │  └─────────────┼─────────────────────────┼──────────────────────────┼──────────────┘  │
 │                │ Strictly Typed Tool Calls│ (Read-Only)              │ Read-Only Context│
 │                ▼                         ▼                          ▼                  │
 │  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
 │  │                   DETERMINISTIC SAFETY PLANE (JAVA STATE MACHINE)                │  │
 │  │                                                                                  │  │
 │  │  ┌───────────────────────┐ ┌──────────────────────┐ ┌─────────────────────────┐ │  │
 │  │  │ MCT Rules Engine      │ │ Evidence Reconciler  │ │ Operator Approval Gate  │ │  │
 │  │  │ (IATA Station Tables) │ │ (Confidence Math)    │ │ (Mandatory Human Signoff│ │  │
 │  │  └──────────┬────────────┘ └──────────┬───────────┘ └────────────┬────────────┘ │  │
 │  │             │                         │                          │              │  │
 │  │             └───────────────────┬─────┴──────────────────────────┘              │  │
 │  │                                 ▼                                               │  │
 │  │               ┌───────────────────────────────────┐                             │  │
 │  │               │ Saga Execution & GDS Servicing    │                             │  │
 │  │               │ (Cryptographic Idempotency / Audit│                             │  │
 │  │               └───────────────────────────────────┘                             │  │
 │  └──────────────────────────────────────────────────────────────────────────────────┘  │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Bounded Cognitive Capabilities: Where AI Adds Genuine Value

### Capability 1: Multi-Source Operational Evidence Summarizer
* **Problem:** During heavy winter fog at Delhi (DEL), dispatchers are overwhelmed with dozens of raw METAR codes, TAF forecasts, ATC ground-stop bulletins, and airline telex notices.
* **AI Agent Role:** Reads structured CADM evidence and outputs a concise 2-sentence operator summary:
  > *"Flight AI-839 marked CANCELLED by airline OCC. Destination DEL visibility has dropped to 50 meters under active CAT III restrictions. All 4 international connections on this flight leg are guaranteed to miss their transits."*

### Capability 2: Rescue Plan Trade-Off Explainer
* **Problem:** Operators reviewing 3 ranked rebooking options need to rapidly explain choices to high-value corporate clients or travel arrangers.
* **AI Agent Role:** Analyzes the trade-off matrix:
  > *"Option 1 (via Mumbai) arrives 4 hours earlier and guarantees a 7h 50m international transit buffer for CAD 0 extra fare. Option 2 (direct tomorrow morning) avoids a middle-of-the-night transit but incurs an 85-minute illegal connection risk violating Flyo safety thresholds."*

### Capability 3: Multilingual Passenger Rescue Assistant
* **Problem:** Stranded passengers at Indian airports speak multiple regional languages (Hindi, Telugu, Tamil, Marathi, English) and experience extreme anxiety during disruptions.
* **AI Agent Role:** Translates verified re-accommodation options and DGCA meal voucher details into clear, empathetic, conversational messages, while deterministic OTP challenges handle confirmation.

---

## 3. Non-Bypassable Deterministic Safety Gates

No AI Agent or LLM prompt may ever bypass the following six foundational constraints:

1. **MCT Safety Inviolability:** An agent cannot recommend or select an option whose arrival buffer is less than the airport’s published MCT.
2. **Human Approval Gate Inviolability:** An agent cannot execute a booking, ticket reissue, or payment transaction. The operator must physically click "Approve" in the Flyo Console.
3. **Geographic Airspace Bounds:** If a flight operates in an unsupported airspace (e.g., Indian airspace with US BTS ML model), the agent is hardcoded to declare the model disabled and route to live radar only.
4. **Idempotency Enforcement:** All tool dispatches require a system-generated SHA-256 idempotency key.
5. **No Direct Database Mutation:** Agents communicate exclusively through strongly typed REST API endpoints with schema validation. They have zero direct database access.
6. **Zero Plaintext Card Access:** Agents operate completely outside the payment perimeter. They have zero tools to inspect or generate payment card details.

---

## 4. Formal Tool Calling Schemas (JSON Schema Specification)

### Tool 1: `SummarizeDisruptionEvidenceTool`
```json
{
  "name": "summarize_disruption_evidence",
  "description": "Synthesizes raw radar status, METAR airport weather, and delay codes into an operator brief.",
  "parameters": {
    "type": "object",
    "required": ["flightNumber", "departureAirport", "arrivalAirport", "radarStatus", "weatherSummary"],
    "properties": {
      "flightNumber": { "type": "string" },
      "departureAirport": { "type": "string", "pattern": "^[A-Z]{3}$" },
      "arrivalAirport": { "type": "string", "pattern": "^[A-Z]{3}$" },
      "radarStatus": { "type": "string", "enum": ["CANCELLED", "DELAYED", "DIVERTED"] },
      "weatherSummary": { "type": "string" }
    }
  }
}
```

### Tool 2: `DispatchApprovedRebookingTool`
```json
{
  "name": "dispatch_approved_rebooking",
  "description": "Dispatches an approved rescue rebooking order to the client GDS or PSS connector. STRICTLY REQUIRES OPERATOR SIGNATURE TOKEN.",
  "parameters": {
    "type": "object",
    "required": ["caseId", "selectedOptionId", "operatorToken", "idempotencyKey"],
    "properties": {
      "caseId": { "type": "string" },
      "selectedOptionId": { "type": "string" },
      "operatorToken": { "type": "string", "description": "Cryptographically signed JWT of approving supervisor" },
      "idempotencyKey": { "type": "string" }
    }
  }
}
```
If `operatorToken` is missing or invalid, the tool call fails closed immediately at the API gateway.
