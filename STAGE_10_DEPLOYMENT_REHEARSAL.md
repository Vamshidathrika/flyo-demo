# STAGE 10: END-TO-END DEPLOYMENT REHEARSAL & RUNBOOK
**Flyo Disruption Management & Automated Passenger Rescue Platform**  
**Rehearsal Executed:** November 8, 2026 | **Target Stack:** Docker Compose / Kubernetes Production Manifests  
**Scope:** PostgreSQL, Redis, Backend (Java 17), ML Service (Python 3.14), Caddy Ingress Gateway

---

## 1. Deployment Topology & Container Architecture

```
                    +----------------------------------------+
                    |        CADDY REVERSE PROXY / TLS       |
                    |              (Port 443 / 80)           |
                    +----------------------------------------+
                                  /            \
                                 /              \
         (API / Static Web)     v                v  (Prediction Calls)
      +-----------------------------+        +--------------------------+
      |  FLYO SPRING BOOT BACKEND   |------->|   FLYO ML MICROSERVICE   |
      |   (Java 17 Temurin, :8080)  |        |    (FastAPI / Uvicorn)   |
      +-----------------------------+        +--------------------------+
                 |              |
                 v              v
      +------------------+  +------------------+
      |  POSTGRESQL 16   |  |     REDIS 7      |
      |  (Port 5432)     |  |   (Port 6379)    |
      +------------------+  +------------------+
```

---

## 2. Production Deployment Sequence & Health Gates

### Step 1: Pre-Flight Environment Secrets Check
Verify that all mandatory production environment variables are exported before container initiation:
```bash
test -n "${SPRING_DATASOURCE_URL}" || { echo "Missing DB URL"; exit 1; }
test -n "${SPRING_DATASOURCE_PASSWORD}" || { echo "Missing DB Password"; exit 1; }
test -n "${JWT_SECRET}" || { echo "Missing JWT Secret"; exit 1; }
test -n "${FLYO_INITIAL_ADMIN_USERNAME}" || { echo "Missing Admin Username"; exit 1; }
test -n "${FLYO_INITIAL_ADMIN_PASSWORD}" || { echo "Missing Admin Password"; exit 1; }
```

### Step 2: Database Migration Gate
Execute Flyway migrations automatically upon backend startup:
```bash
# Validates schema versions V1 through V9
curl -s http://localhost:8080/actuator/health | jq .status
# Expected: "UP"
```

### Step 3: ML Microservice Readiness Gate
Verify artifact loading and memory allocation:
```bash
curl -s -H "X-API-Key: ${FLYO_MONITORING_API_KEY}" http://localhost:8000/monitoring/health | jq .status
# Expected: "HEALTHY", "models_loaded": true
```

### Step 4: Initial Administrative Operator Provisioning
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"${FLYO_INITIAL_ADMIN_USERNAME}\",\"password\":\"${FLYO_INITIAL_ADMIN_PASSWORD}\"}"
# Expected: HTTP 200 OK with Bearer JWT
```

---

## 3. Rollback & Failover Procedures

1. **Schema Migration Failure**:
   - Flyway runs within transactional DDL. If a migration fails, PostgreSQL rolls back automatically.
   - If manual rollback is required:
     ```bash
     psql -U flyo_prod_admin -d flyo_db -f scripts/rollback/v9_rollback.sql
     ```
2. **Backend Application Hang / OOM**:
   - Kubernetes liveness probes restart containers if `/actuator/health/liveness` fails 3 times consecutively.
   - Distributed saga leases automatically expire after 60 seconds, allowing another healthy node to resume orphaned rebooking workflows.

---

## 4. Rehearsal Verdict

The deployment rehearsal was executed successfully on local staging infrastructure. Zero manual database fixes or ad-hoc patches were required during startup.
