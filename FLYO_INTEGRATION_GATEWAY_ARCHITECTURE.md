# Flyo Customer Integration Gateway (FIG) Architecture

**Document Status:** Enterprise Systems Architecture Specification  
**Author:** Principal Enterprise Integration Architect & Cloud Security Engineer  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Architectural Mission & Zero-Trust Ingress

Enterprise airlines and regulated financial travel management companies will never open inbound public firewall ports into their core PSS, DCS, or OCC database networks. Furthermore, transmitting unmasked passenger Personally Identifiable Information (PII) or credit cardholder data (PCI) outside their sovereign cloud boundary violates data privacy regulations (e.g., India's DPDP Act 2023, GDPR) and PCI-DSS Level 1 compliance.

The **Flyo Customer Integration Gateway (FIG)** solves this enterprise boundary challenge. FIG is a lightweight, customer-hosted software appliance deployed directly inside the client's internal network (VPC, private cloud, or datacenter DMZ).

FIG acts as a **local secure proxy**:
1. Connects locally to the client's internal PSS, OCC, AODB, and GDS endpoints.
2. Sanitizes and tokenizes sensitive passenger identities and payment data *before* egress.
3. Establishes an **outbound-only, cryptographically authenticated tunnel** (mTLS / gRPC) to Flyo Intelligence Cloud.
4. Executes approved servicing commands locally against client enterprise systems.

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                      CUSTOMER PRIVATE ENTERPRISE BOUNDARY (VPC / DMZ)                  │
 │                                                                                        │
 │  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────────────┐ │
 │  │ Airline OCC / AODB   │  │ Airline Altéa / NS    │  │ Travel Agency GDS / Mid-Off  │ │
 │  │ Internal AIDX/MQ     │  │ Digital Connect / REST│  │ Sabre PCC / Travelport MQ    │ │
 │  └──────────┬───────────┘  └──────────┬───────────┘  └──────────────┬───────────────┘ │
 │             │                         │                             │                  │
 │             ▼                         ▼                             ▼                  │
 │  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
 │  │                       FLYO CUSTOMER INTEGRATION GATEWAY (FIG)                    │  │
 │  │                                                                                  │  │
 │  │  ┌───────────────────────┐ ┌──────────────────────┐ ┌─────────────────────────┐  │  │
 │  │  │ Protocol Normalization│ │ PII Tokenizer &      │ │ Local Credential Vault  │  │  │
 │  │  │ (AIDX, JSON, EDIFACT) │ │ Masking Engine       │ │ (HashiCorp / AWS KMS)   │  │  │
 │  │  └──────────┬────────────┘ └──────────┬───────────┘ └────────────┬────────────┘  │  │
 │  │             │                         │                          │               │  │
 │  │             └───────────────────┬─────┴──────────────────────────┘               │  │
 │  │                                 ▼                                                │  │
 │  │                    ┌────────────────────────────┐                                │  │
 │  │                    │ Durable Local Event Buffer │                                │  │
 │  │                    │ (Embedded RocksDB / Outbox)│                                │  │
 │  │                    └────────────┬───────────────┘                                │  │
 │  │                                 │                                                │  │
 │  │  ┌──────────────────────────────┴───────────────┐                                │  │
 │  │  │ Outbound Secure mTLS / gRPC Client Connector │                                │  │
 │  │  └──────────────────────────────┬───────────────┘                                │  │
 │  └─────────────────────────────────┼────────────────────────────────────────────────┘  │
 └────────────────────────────────────┼───────────────────────────────────────────────────┘
                                      │ Outbound Only (Port 443 / mTLS)
                                      │ No Inbound Public Firewall Ports
                                      ▼
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                              FLYO CORE INTELLIGENCE CLOUD                              │
 │  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
 │  │ Ingress TLS Terminus -> CADM Event Bus -> Disruption State Machine -> Operator UI│  │
 │  └──────────────────────────────────────────────────────────────────────────────────┘  │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Gateway Deployment Topologies

FIG is packaged and distributed via hardened, signed container images supporting three primary deployment targets:

### Topology 1: Kubernetes Workload (EKS, GKE, AKS, OpenShift)
* **Packaging:** Helm Chart deploying an active-passive or active-active stateless replica set with an embedded persistent volume for offline buffering.
* **Network Isolation:** Runs within a dedicated `flyo-system` namespace. Ingress policies restrict communication exclusively to designated PSS/DCS internal hostnames.
* **Egress Policy:** Egress restricted via Kubernetes NetworkPolicy to a single external hostname: `gateway-ingress.<tenant>.flyo.aero:443`.

### Topology 2: Docker / Container Appliance on Enterprise Linux (RHEL, Rocky, Ubuntu)
* **Packaging:** Single `docker-compose.yml` or Podman bundle containing the FIG Core container and an embedded Redis/RocksDB persistence volume.
* **Resource Footprint:** 2 vCPU, 4 GB RAM, 20 GB encrypted SSD storage. Capable of processing 500 flight status updates and 50 PNR lookups per second.

### Topology 3: Virtual Appliance (VMware ESXi, Nutanix, AWS AMI)
* **Packaging:** Hardened OVA / AMI image with pre-configured CIS Benchmark Level 2 OS hardening, automated security patching, and zero default root passwords.

---

## 3. Core Internal Gateway Subsystems

### 3.1. Protocol Ingress & Adapters
The Gateway hosts modular protocol listeners:
* **AIDX Listener:** Consumes XML messages over WebSphere MQ, RabbitMQ, or HTTPS webhook from the airline's OCC or airport AODB.
* **PSS Digital Proxy:** Issues authenticated local REST/SOAP calls to Amadeus Altéa or Navitaire New Skies endpoints.
* **GDS Sweeper:** Connects to agency Sabre/Travelport queue banks to sweep disrupted PNRs every 60 seconds.
* **Normalizer:** Immediately transforms disparate payloads into standard Flyo Canonical Aviation Data Model (CADM) structs.

### 3.2. PII Tokenizer & Data Masking Engine
Before any record leaves the customer VPC, sensitive passenger data is stripped or tokenized:
* **Passenger Names:** `Ananya Sharma` is mapped to a tenant-isolated token: `PAX-9f82c401`. The plaintext-to-token map is stored exclusively in the customer’s local Gateway cache; Flyo Cloud only sees the pseudonymized token.
* **Phone Numbers:** E.164 phone numbers (e.g., `+91 98765 43210`) are encrypted using a tenant-specific asymmetric public key (`RSA-OAEP-4096`). Flyo Cloud cannot view the raw phone number; only the customer's authorized WhatsApp Business Gateway can decrypt it.
* **Payment Credentials:** **Absolute PCI Quarantine.** Credit card numbers and CVVs are strictly forbidden from entering Flyo Cloud. Payments are handled via tokenized virtual account numbers or direct client bank settlement proxies.

### 3.3. Customer Credential & Secret Vault
* The client’s master PSS agent credentials, Navitaire API keys, and Sabre PCC passwords never leave the customer’s private network.
* The Gateway integrates directly with the client’s native key management system (AWS KMS, Azure Key Vault, HashiCorp Vault, or CyberArk) to retrieve credentials at runtime for servicing execution.

### 3.4. Local Event Buffer & WAN Outage Resilience
Aviation operations cannot tolerate network drops. If the client’s datacenter loses internet connectivity to Flyo Cloud:
* FIG’s embedded RocksDB persistent queue buffers all incoming operational events and PNR alerts locally.
* Upon WAN reconnection, FIG automatically replays buffered events with guaranteed idempotency and deduplication headers (`X-Flyo-Idempotency-Key`).
* Local circuit breakers prevent cascading saturation of internal airline APIs during peak disruption events.

### 3.5. Local Servicing Execution Proxy
When an operator approves a rescue rebooking in Flyo Console, the execution command is sent through the secure mTLS stream to the Gateway:
1. FIG validates the cryptographic signature of the approval order.
2. FIG retrieves the client’s local PSS/GDS credentials from the vault.
3. FIG dispatches the authorized `OrderChange` or `EnhancedAirBook` call internally to the PSS.
4. FIG verifies the response, captures the authoritative new PNR, and streams the verified receipt back to Flyo Cloud.
