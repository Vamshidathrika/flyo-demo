# FLYO RESCUE — OCC DYNAMIC DATA SOURCE MATRIX

## 1. Executive Summary & Zero-Hardcoding Mandate
This document certifies that the Flyo Rescue Airline Operations Control Center (OCC) Console operates entirely on **100% dynamic, database-backed, or authenticated provider-sourced data**. No passenger identities, PNRs, flight schedules, minimum connecting times (MCT), fare differentials, or virtual card numbers are hardcoded in application logic or user interface templates.

Every field displayed across the 4 core OCC views is mapped to an authoritative PostgreSQL table, Redis cache key, or external provider API, with explicit zero-hardcoding enforcement mechanisms.

---

## 2. OCC View 1: Trip Monitor (Flight Stream & Disruption Surveillance)

| UI Component | Field / Metric | Authoritative Data Source | Protocol / Ingestion Channel | Default / Empty Policy | Hardcoding Prevention Mechanism |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Flight Stream Table** | Flight Number | `flights.flight_number` | AviationStack API / Airline GDS Stream | Displays `"—"` if unassigned | Queried via `FlightRepository.findAllActive()` |
| **Flight Stream Table** | Carrier / Airline | `flights.carrier_code`, `flights.carrier_name` | IATA Airline Registry / GDS feed | Derived from IATA 2-letter prefix | Dynamic lookup; no static airline lists |
| **Flight Stream Table** | Origin & Destination | `flights.origin_iata`, `flights.destination_iata` | ICAO/IATA Airport Database | None (mandatory schema constraint) | Validated against 3-letter IATA regex `^[A-Z]{3}$` |
| **Flight Stream Table** | Scheduled Departure/Arrival | `flights.scheduled_departure_time`, `flights.scheduled_arrival_time` | GDS PNR / OAG Flight Schedule | UTC ISO-8601 Timestamp | Parsed dynamically with zone offset |
| **Flight Stream Table** | Estimated Departure/Arrival | `flights.estimated_departure_time`, `flights.estimated_arrival_time` | AviationStack Live Flight Feed | Defaults to scheduled time if no delay reported | Updated in real-time by webhook/poller |
| **Flight Stream Table** | Disruption Status | `flights.status` (`ON_TIME`, `DELAYED`, `CANCELLED`) | Aviation Provider Webhook / Radar API | `SCHEDULED` | Enum mapped to dynamic database column |
| **Flight Stream Table** | Affected Passengers | `COUNT(bookings.id)` where `flight_id = flight.id` | PostgreSQL Aggregation Query | `0` passengers | Dynamic relational count |
| **Flight Stream Table** | At-Risk Onward Connections | Computed via `MctRulesService` against `airport_mct_rules` | Dynamic SQL join: `bookings` $\bowtie$ `airport_mct_rules` | Marked `UNKNOWN` if rule missing | If airport rule not approved in DB, sets `requiresManualReview = true` |
| **Flight Stream Table** | Disruption Severity Score | `disruption_events.severity` | Machine Learning Inference Engine (`/predict/delay`) | `NORMAL` (Score: 0.0) | Feature vector computed from live weather & delay delta |
| **Filter & Search Bar** | Carrier / Route Filter | `SELECT DISTINCT origin_iata, destination_iata FROM flights` | Dynamic JPA query | Dynamic dropdown populate | Zero hardcoded filter options |

---

## 3. OCC View 2: Incident Recovery (Active Cases, Options & Execution)

| UI Component | Field / Metric | Authoritative Data Source | Protocol / Ingestion Channel | Default / Empty Policy | Hardcoding Prevention Mechanism |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Incident Header** | Case Reference ID | `cases.case_reference` | UUIDv4 / Sequence generator | Generated on case creation | Unique constraint in database |
| **Incident Header** | Passenger Name & Contact | `bookings.passenger_name`, `bookings.passenger_phone` | Ingested PNR record | Redacted according to RBAC role | Extracted from passenger booking table |
| **Incident Header** | Original PNR & Itinerary | `bookings.pnr`, `bookings.itinerary` | Airline GDS Ingestion | Mandatory field | Normalized relational entity |
| **Disruption Context** | Disruption Type & Cause | `disruption_events.type`, `disruption_events.root_cause` | AviationStack / Weather API | Logged as `IN_FLIGHT_CHANGE` | Populated via event payload |
| **Disruption Context** | Required MCT vs Buffer | `airport_mct_rules.standard_mct_minutes` vs `buffer_minutes` | Versioned DB table `airport_mct_rules` | If missing, flags `requiresManualReview = true` | Zero code constants; audited DB lookup |
| **Rebooking Options Grid** | Alternative Flight Number | `rebooking_options.flight_number` | Amadeus GDS `/v2/shopping/flight-offers` | Empty list if no inventory | Scraped/queried live from GDS sandbox/live |
| **Rebooking Options Grid** | Departure / Arrival Times | `rebooking_options.departure_time`, `arrival_time` | GDS Flight Offers API | Live UTC timestamp | Extracted from GDS response object |
| **Rebooking Options Grid** | Fare Differential & Currency | `rebooking_options.fare_difference_cents`, `currency` | GDS Price Quote vs Original Ticket | `0.00` if identical fare class | Calculated dynamically per fare rule |
| **Rebooking Options Grid** | Recommendation Score & Rank | `rebooking_options.confidence_score`, `model_version` | Python Predictive Engine API | Computed rank (1 to N) | Scored in real-time by ML service |
| **Execution State Machine** | Current Phase | `cases.state` | Spring State Machine (`CaseStateMachineService`) | `DETECTED` | Validated transition guards |
| **Payment Settlement** | Virtual Card Token (VCC) | `payment_records.vcc_token`, `payment_records.masked_card` | Stripe Issuing API / Card Processor | Labelled `[SIMULATION VCC]` in test | Tokenized; no raw card numbers stored |
| **Ticket Confirmation** | Issued E-Ticket / PNR | `rebook_results.new_pnr`, `rebook_results.ticket_number` | GDS Order Confirmation | Labelled `[SIMULATION TICKET]` if mock | Verified against booking provider |

---

## 4. OCC View 3: Operations Control (Infrastructure, Sagas & Telemetry)

| UI Component | Field / Metric | Authoritative Data Source | Protocol / Ingestion Channel | Default / Empty Policy | Hardcoding Prevention Mechanism |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Provider Health Grid** | Amadeus GDS Status | Circuit Breaker: `amadeusGds` | Resilience4j Metrics / Actuator | `UNKNOWN` / `DOWN` on timeout | Live ping & failure rate tracker |
| **Provider Health Grid** | Stripe Payments Status | Circuit Breaker: `stripePayments` | Resilience4j Metrics / Actuator | `UNKNOWN` / `DOWN` on timeout | Live ping & failure rate tracker |
| **Provider Health Grid** | Weather Service Status | Circuit Breaker: `openMeteoWeather` | Open-Meteo `/v1/forecast` probe | `DEGRADED` fallback to cache | Dynamic health check endpoint |
| **Provider Health Grid** | Flight Radar Status | Circuit Breaker: `aviationStack` | AviationStack status check | `DEGRADED` fallback to GDS | Dynamic health check endpoint |
| **Outbox & Saga Monitor** | Pending Outbox Messages | `SELECT COUNT(*) FROM outbox_messages WHERE status = 'PENDING'` | PostgreSQL transactional outbox | `0` | Live polling query |
| **Outbox & Saga Monitor** | Active Saga Orchestrations | `SELECT COUNT(*) FROM saga_instances WHERE status = 'IN_PROGRESS'` | Spring Saga Coordinator table | `0` | Dynamic count query |
| **ML Predictive Telemetry** | Active Model Version | Python ML Service `/health` endpoint | HTTP GET `/v3/predictive-engine/health` | `v3.1-production-retrained` | Dynamic probe of ML microservice |
| **ML Predictive Telemetry** | Feature Drift Metric (PSI) | Python ML Service `/telemetry/drift` | Streaming KS-test & PSI calculation | `0.000` (Baseline) | Calculated over rolling 500 predictions |
| **ML Predictive Telemetry** | Brier Calibration Score | Python ML Service `/telemetry/calibration` | Continuous scoring vs ground truth | `0.000` | Dynamic evaluation pipeline |
| **Database Pool Monitor** | Active Connections / Idle | HikariCP MBean metrics | HikariCP Connection Pool Actuator | Live JMX count | Sourced directly from JVM runtime |

---

## 5. OCC View 4: Audit Trail (Cryptographic Event Ledger)

| UI Component | Field / Metric | Authoritative Data Source | Protocol / Ingestion Channel | Default / Empty Policy | Hardcoding Prevention Mechanism |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Event Stream Table** | Audit Event Sequence ID | `audit_events.id` (BIGINT AUTO_INCREMENT) | PostgreSQL Primary Key | None | Auto-sequenced strictly ascending |
| **Event Stream Table** | Timestamp | `audit_events.created_at` | Database `DEFAULT CURRENT_TIMESTAMP` | UTC Instant | Non-updatable database column |
| **Event Stream Table** | Correlation / Case ID | `audit_events.correlation_id` | MDC (Mapped Diagnostic Context) | System UUID | Injected at API boundary filter |
| **Event Stream Table** | Actor Identity | `audit_events.actor_id`, `audit_events.actor_role` | Spring Security `SecurityContextHolder` | `SYSTEM_AUTOMATION` | Derived from authenticated JWT/Session |
| **Event Stream Table** | Event Type & Action | `audit_events.event_type` | Immutable Domain Event Enum | Validated enum string | Strongly-typed event dispatchers |
| **Event Stream Table** | Payload Digest | `audit_events.payload_hash` | SHA-256 of canonical JSON payload | Generated at persistence time | Hash verification on read |
| **Event Stream Table** | Previous Event Hash | `audit_events.previous_event_hash` | SHA-256 of preceding audit record | Genesis hash `0000...0000` for ID 1 | Cryptographic blockchain-style chain |
| **Integrity Indicator** | Chain Integrity Status | `AuditVerificationService.verifyLedgerIntegrity()` | Batch SHA-256 recalculation on query | `VERIFIED` or `TAMPERED` | Run on demand; alerts on mismatch |

---

## 6. Verification and Compliance Attestation
- **Zero In-Memory Static Dictionaries**: All operational entities are persisted in relational tables with foreign keys and cascade rules.
- **Empty-Database Safety**: If the database contains zero records, all OCC views render clean, empty states without synthetic fallback rows or crashing.
- **Role-Based Access Control**: Sensitive passenger data (phone numbers, full names) is dynamically redacted unless the requesting operator possesses `ROLE_OCC_SUPERVISOR` or `ROLE_INCIDENT_COMMANDER`.
