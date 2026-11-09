# STAGE 10: CONTROLLED PILOT DEPLOYMENT RUNBOOK & OPERATIONAL GOVERNANCE
**Flyo Disruption Management Platform**  
**Commercial Pilot Specification:** Single-Carrier, Single-Corridor Authorized Pilot

---

## 1. Pilot Scope & Boundary Definition

To eliminate operational risk and prevent runaway financial exposure, the Stage 10 commercial pilot is strictly constrained:

```
┌─────────────────────────────────┬────────────────────────────────────────────────────────┐
│ Parameter                       │ Pilot Constraint                                       │
├─────────────────────────────────┼────────────────────────────────────────────────────────┤
│ Target Airline Partner          │ Air India (AI) / Star Alliance Interline               │
│ Geographic Corridor             │ Domestic Trunk Corridor: DEL ⇄ BOM and HYD ⇄ DEL      │
│ Supported Rescue Operations     │ Same-Day Rebooking & Connecting Flight Protection      │
│ Authorized Pilot Operators      │ Designated Operations Shift Supervisors (Max: 5 users) │
│ Transaction Limit per Case      │ ₹15,000 INR maximum fare difference                    │
│ Daily Total Pilot Budget Cap    │ ₹500,000 INR (Hard limit enforced in Redis/Postgres)   │
│ Autonomous Mode                 │ DISABLED — 100% Human Operator Approval Mandatory      │
└─────────────────────────────────┴────────────────────────────────────────────────────────┘
```

---

## 2. Infrastructure Deployment Topology

- **Primary Cloud Host**: AWS Mumbai (`ap-south-1`)
- **Backend Service**: 2x Containerized Spring Boot 3.3.4 (ECS Fargate / EKS)
- **Database**: AWS Aurora PostgreSQL 16 (Multi-AZ with automated backup)
- **Cache & Distributed Lock**: AWS ElastiCache Redis 7.2 (Cluster mode)
- **Egress Security**: Dedicated NAT Gateway with Elastic IP allow-listed by Amadeus and Stripe.

---

## 3. Incident Escalation & Rollback Plan

### Severity 1: Double Booking or Ledger Discrepancy
- **Action**: Immediate kill-switch via Actuator `/actuator/pause-pilot`.
- **Mitigation**: Automated void of all uncaptured virtual cards; manual PNR inspection via Amadeus Altéa GUI.

### Severity 2: Provider API Disruption (Amadeus 5xx Outage)
- **Action**: Circuit breaker trips OPEN; incoming disruptions quarantined in `MANUAL_INTERVENTION_REQUIRED`.
- **Fallback**: Operators switch to manual phone/GDS terminal rebooking.

### Rollback Procedure
```bash
# 1. Immediate traffic drain
kubectl scale deployment flyo-backend --replicas=0

# 2. Revert Flyway schema migration if required
mvn -f flyo-rescue-demo/backend/pom.xml flyway:undo

# 3. Restore clean PostgreSQL snapshot
pg_restore -d flyodb_prod /backups/flyo_pre_pilot_snapshot.dump
```
