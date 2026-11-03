# Flyo Rescue Agent — Autonomous Flight Disruption Recovery Platform

> **Production Disruption Recovery Engine · Flyo Operations**  
> Autonomous flight rescue, connection buffer evaluation, automated partner portal rebooking via Playwright, and passenger WhatsApp recovery workflows.

---

## 1. Executive Summary

**Flyo Rescue Agent** is an autonomous disruption recovery platform built for flight operations. When an airline unexpectedly cancels or delays a flight, Flyo:
1. **Instantly detects** the disruption event.
2. **Deterministically evaluates** onward connection safety and downstream accommodation risks using rigid Minimum Connecting Time (MCT) connection buffer rules.
3. **Ranks recovery options** deterministically (code over LLM for money & ticketing).
4. **Requires explicit Flyo Operator approval** via Human-in-the-Loop (HITL) gates.
5. **Automates partner portal rebooking** via headless browser automation (Playwright).
6. **Delivers interactive WhatsApp recovery journeys** with OTP verification, virtual card (VCC) authorization, and real-time reconciliation.

---

## 2. Repository Structure

```
├── Dockerfile                         # Production container image
├── docker-compose.yml                 # Multi-service local & production stack
├── PLATFORM-OVERVIEW.md               # Detailed architectural specification & workflows
├── docs/                              # System documentation & specs
│   ├── FINAL_PRODUCTION_READINESS_REPORT.md
│   ├── WHATSAPP_RESCUE_WORKFLOW.md
│   ├── WHATSAPP_RESCUE_SEQUENCE.md
│   ├── WHATSAPP_RESCUE_STATE_MACHINE.md
│   └── diagrams/                      # Workflow and sequence SVG & Mermaid diagrams
└── flyo-rescue-demo/                  # Application Codebase
    ├── backend/                       # Spring Boot 3.3 (Java 17/21/26) REST API
    │   ├── src/main/java/             # State machine, ranking engine, OTP & adapters
    │   ├── src/test/java/             # 94 automated integration & unit tests
    │   └── pom.xml
    └── agent/                         # Playwright browser worker & verification suite
        ├── rebook.ts                  # Autonomous portal rebooking worker
        ├── test-full-operational-suite.js
        └── package.json
```

---

## 3. Quickstart & Local Run

### Prerequisites
- **Java 17+** (OpenJDK 17, 21, or 26)
- **Maven 3.8+**
- **Node.js 20+**

### Step-by-Step

1. **Install Browser Worker Dependencies:**
   ```bash
   cd flyo-rescue-demo/agent
   npm install
   npx playwright install chromium
   cd ../..
   ```

2. **Run Backend Test Suite:**
   ```bash
   cd flyo-rescue-demo/backend
   mvn clean test
   ```
   *(94/94 tests passing)*

3. **Start Flyo Services with Docker Compose:**
   ```bash
   docker compose up -d
   ```
   Or run the backend directly:
   ```bash
   cd flyo-rescue-demo/backend
   mvn spring-boot:run
   ```

---

## 4. Key Architectural Guarantees

- **Deterministic Financial & Booking Rules:** Timezone calculations, transit buffer checks, and fare reconciliation are computed deterministically.
- **Human-in-the-Loop Gate:** Financial commitments (+₹4,200) require operator approval before card generation and rebooking dispatch.
- **Strict Idempotency:** Composite keys (`caseId + optionId`) ensure no duplicate airline reservations or charges occur upon retries.
- **Audit-Ready Outbox:** Durable state transitions and event outbox tracking for all recovery cases.
