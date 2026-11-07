# Client Deployment Models and Multi-Tenant Architecture

**Document Status:** Enterprise Platform Architecture Specification  
**Author:** Principal Cloud Architect & Enterprise Systems Specialist  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Comparison of Enterprise Deployment Models

Deploying software into enterprise aviation environments requires balancing data sovereignty, regulatory mandates, operational latency, and maintenance overhead. Flyo evaluates three distinct deployment models:

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                   DEPLOYMENT MODEL ARCHITECTURES                                 │
│                                                                                                  │
│   MODEL 1: DIRECT MULTI-TENANT SAAS        MODEL 2: HYBRID GATEWAY (RECOMMENDED)                 │
│   ┌───────────────────────────────┐        ┌─────────────────────────┐  ┌─────────────────────┐  │
│   │ Client A (OTA / Mid-Agency)   │        │ Client VPC (Airline/TMC)│  │ Flyo Cloud SaaS     │  │
│   │ ─ GDS / NDC Direct Connect    │        │ ─ PSS / OCC (Local API) │  │ ─ State Machine     │  │
│   │ ─ Public IP Allowlist         │        │ ─ Flyo Gateway (FIG)    │  │ ─ Ranking Engine    │  │
│   │ ─ Flyo Hosted Multitenant DB  │        │ ─ Tokenized PII / PCI   │  │ ─ Operator Console  │  │
│   └───────────────────────────────┘        └────────────┬────────────┘  └──────────┬──────────┘  │
│                                                         │ mTLS Outbound            │             │
│   MODEL 3: AIR-GAPPED PRIVATE VPC                       └──────────────────────────┘             │
│   ┌───────────────────────────────────────────────────────────────────────────────────────────┐  │
│   │ Client Dedicated Sovereign Cloud (AWS GovCloud, MeitY-Empaneled Cloud, On-Premises OpenShift)│  │
│   │ ─ 100% Flyo Stack (Backend, Postgres, ML FastAPI, Frontend, Redis) running in customer VPC│  │
│   └───────────────────────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.1. Detailed Trade-off Matrix

| Evaluation Dimension | Model 1: Multi-Tenant SaaS | Model 2: Hybrid Gateway (Recommended) | Model 3: Air-Gapped Private Cloud |
| :--- | :--- | :--- | :--- |
| **Target Customers** | Mid-market OTAs, regional travel desks | Tier-1 Airlines, major corporate TMCs | Sovereign flag carriers, defense/military |
| **Data Sovereignty** | Tenant data resides in Flyo cloud region | PII/PCI tokenized locally; master data remains in VPC | 100% data remains strictly inside client VPC |
| **Network Security** | Requires customer to open API to Flyo IPs | **Zero inbound firewall holes.** Outbound mTLS only | Fully internal private network; zero public egress |
| **Credential Safety** | PSS/GDS credentials stored in Flyo Vault | Master credentials remain in client KMS vault | Credentials remain inside client KMS |
| **Integration Latency**| Dependent on public internet WAN latency | Sub-millisecond local PSS queries; async sync | Pure LAN latency (< 5ms) |
| **Deployment Complexity**| Low (immediate onboarding via API keys) | Moderate (Helm chart or Docker container in VPC) | High (Full enterprise infrastructure provisioning) |
| **Maintenance & Updates**| Zero customer effort; continuous CD | Automated Gateway container auto-pull | Customer-managed change advisory boards (CAB) |
| **Regulatory Fit (DPDP)**| Requires explicit cross-border/cloud DPA | Fully compliant (Data Fiduciary maintains control)| Maximum regulatory compliance |
| **Cost Profile** | Pay-as-you-go subscription / usage-based | Annual platform license + gateway support fee | High enterprise license + infra overhead |

---

## 2. Multi-Tenant Architecture & Data Isolation

The current prototype of Flyo has **zero multi-tenant awareness**. Every table in the Flyway migrations (`bookings`, `rescue_cases`, `virtual_cards`) lacks a `tenant_id` foreign key. To deploy into multiple clients, Flyo must implement rigorous multi-tenancy.

### 2.1. Tenant Isolation Model
Flyo adopts a **Hybrid Shared-Database, Row-Level Security (RLS) & Tenant-Specific Encryption** model:

```sql
-- Architectural Target Schema for PostgreSQL Multi-Tenancy
ALTER TABLE bookings ADD COLUMN tenant_id VARCHAR(64) NOT NULL;
ALTER TABLE rescue_cases ADD COLUMN tenant_id VARCHAR(64) NOT NULL;
ALTER TABLE rebook_options ADD COLUMN tenant_id VARCHAR(64) NOT NULL;
ALTER TABLE audit_events ADD COLUMN tenant_id VARCHAR(64) NOT NULL;

-- Enable PostgreSQL Row Level Security (RLS)
ALTER TABLE bookings ENABLE ROW LEVEL SECURITY;
ALTER TABLE rescue_cases ENABLE ROW LEVEL SECURITY;

-- Enforce Tenant Boundary Policy via Session Variable
CREATE POLICY tenant_isolation_policy ON bookings
    AS RESTRICTIVE
    USING (tenant_id = current_setting('app.current_tenant_id', true));

CREATE POLICY tenant_isolation_rescue_cases ON rescue_cases
    AS RESTRICTIVE
    USING (tenant_id = current_setting('app.current_tenant_id', true));
```

### 2.2. Application-Level Tenant Context (Spring Boot)
In the Java backend, a `TenantContextFilter` extracts the tenant identity from verified JWT claims (`iss` / `tenant_id`) and sets a thread-local context:
```java
public class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    public static void setTenantId(String tenantId) { CURRENT_TENANT.set(tenantId); }
    public static String getTenantId() { return CURRENT_TENANT.get(); }
    public static void clear() { CURRENT_TENANT.remove(); }
}
```
Every SQL query issued by Spring Data JPA automatically binds `SET LOCAL app.current_tenant_id = 'TENANT-XYZ'`, ensuring that even a catastrophic SQL injection bug cannot expose another customer's passenger data.

---

## 3. Tenant Configuration & Model Eligibility Engine

Different clients operate under different geographic airspaces, regulatory mandates, and commercial agreements. Flyo provisions a tenant configuration record:

```json
{
  "tenantId": "TENANT-AIRINDIA-EXP",
  "companyName": "Air India Express Limited",
  "regulatoryRegion": "INDIA_DGCA",
  "dataResidencyZone": "ap-south-1 (Mumbai)",
  "connectors": {
    "flightTelemetryProvider": "CIRIUM_STREAM",
    "pssProvider": "NAVITAIRE_NEW_SKIES",
    "gdsProvider": "NONE",
    "whatsappProvider": "META_CLOUD_API",
    "vccProvider": "DIRECT_HDFC_COMMERCIAL_API"
  },
  "mlEligibility": {
    "usDomesticBtsModelEnabled": false,
    "indianDomesticModelEnabled": true,
    "indianModelVersion": "v1.2.0-ind-bom-del",
    "fallbackStrategy": "LIVE_RADAR_AND_WEATHER_ONLY"
  },
  "operationalPolicies": {
    "domesticMctBufferMinutes": 75,
    "internationalMctBufferMinutes": 120,
    "mandatoryHumanApproval": true,
    "passengerSelfServiceOtp": true,
    "dgcaRefreshmentThresholdHours": 2,
    "dgcaHotelThresholdHours": 24
  }
}
```

### 3.1. Strict ML Model Eligibility Enforcement
> [!CRITICAL]
> **The US BTS Model Constraint:** Flyo's existing machine learning artifact (`model_v2_extreme_delay.json` / `model_v2_cancellation.json`) was trained exclusively on US Department of Transportation BTS data (US domestic flights, American carrier hub mechanics, FAA weather delays).
> 
> As codified in `ml/service/predictor.py`:
> **Under no circumstances may the US BTS model serve predictions for Indian or international carriers.**
> 
> When an Indian client (e.g. Air India, IndiGo) is provisioned:
> 1. The configuration flag `usDomesticBtsModelEnabled` is forced to `false`.
> 2. The system checks if an officially validated Indian airspace model (`indianDomesticModelEnabled`) is registered for that tenant.
> 3. If no validated Indian model exists, Flyo falls back gracefully to `PredictionStatus.UNSUPPORTED_AIRSPACE`, relying strictly on authoritative radar feeds, Open-Meteo airport weather telemetry, and human operator review.
