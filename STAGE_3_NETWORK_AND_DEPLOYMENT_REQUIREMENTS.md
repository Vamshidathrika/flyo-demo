# FLYO — STAGE 3 NETWORK & DEPLOYMENT REQUIREMENTS
## Infrastructure, Topology, Network Egress & Clustering Constraints

**Architecture Lead:** Production Reliability Engineer & Cloud Infrastructure Architect  
**Scope:** Client Staging, Hybrid Cloud & On-Premises OCC Integration

---

## 1. Network Topologies & Egress Firewall Rules

Flyo is designed to deploy within a client's Virtual Private Cloud (VPC) or airline private data center. Outbound connectivity must be granted for the following HTTPS/TLS 1.3 endpoints:

| Destination Provider | Hostname / Domain | Port | Protocol | Purpose |
|----------------------|-------------------|------|----------|---------|
| **Meta WhatsApp API** | `graph.facebook.com` | 443 | HTTPS / TLS 1.3 | Passenger notices & interactive cards |
| **Stripe Issuing** | `api.stripe.com` | 443 | HTTPS / TLS 1.3 | Tokenized corporate VCC issuance |
| **Aviationstack** | `api.aviationstack.com` | 443 | HTTPS / TLS 1.3 | Flight tracking telemetry |
| **Amadeus GDS** | `test.api.amadeus.com` | 443 | HTTPS / TLS 1.3 | Flight search & PNR retrieval |
| **Sabre GDS** | `api.cert.platform.sabre.com` | 443 | HTTPS / TLS 1.3 | GDS availability & ticket checking |
| **Salesforce CRM** | `*.salesforce.com` | 443 | HTTPS / TLS 1.3 | Case sync & customer record updates |
| **Open-Meteo** | `api.open-meteo.com` | 443 | HTTPS / TLS 1.3 | Real-time weather telemetry |

---

## 2. Inbound Webhook Ingress Architecture

- **Public Reverse Proxy / WAF**: Inbound webhooks (`/api/whatsapp/webhook`, `/api/webhooks/**`) must terminate at an enterprise ingress controller (e.g. AWS ALB, Cloudflare, Caddy) with TLS 1.3.
- **CIDR Restriction**: If possible, restrict ingress IP ranges to Meta Webhook IPs (`157.240.0.0/16`, `31.13.64.0/18`) and Stripe Webhook IPs.
- **Internal Microservice Network**: All internal service communications (Spring Boot to PostgreSQL, Spring Boot to Python FastAPI ML service) run over isolated, private cluster networks without public IP addresses.

---

## 3. Horizontal Pod Autoscaling & Cluster Coordination

To scale Flyo horizontally beyond a single instance:
1. **Shared Rate Limiting**: The database table `shared_rate_limits` must be backed by a managed PostgreSQL 14+ cluster.
2. **Lease Reclamation**: Outbox and Saga workers use row-level leases (`locked_by`, `locked_at`). When a pod terminates unexpectedly, surviving pods automatically reclaim abandoned leases after 120 seconds.
3. **Graceful Draining**: The Spring Boot container handles `SIGTERM` by halting outbox batch claiming and draining active network dispatches within a 30-second window.
4. **Single-Instance Enforcement**: If shared database/Redis coordination is disabled (`flyo.rate-limiter.shared-storage.enabled=false`), the application enforces a strict single-pod deployment limit to prevent uncoordinated rate limit breaches.
