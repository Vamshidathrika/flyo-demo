# Flyo Production Infrastructure Remediation Plan

**Document Status:** Approved Infrastructure Architecture Plan  
**Role:** Principal Cloud Infrastructure Architect & DevOps Specialist  
**Target Repository:** `/Users/nani/Downloads/flyo`  
**Date:** November 2026  

---

## 1. Forensic Infrastructure Gap Audit

Inspection of the repository's root [`Dockerfile`](file:///Users/nani/Downloads/flyo/Dockerfile) and [`docker-compose.yml`](file:///Users/nani/Downloads/flyo/docker-compose.yml) reveals severe infrastructure blockers:

1. **Alpine Linux Playwright Crash:** The production runner stage uses `eclipse-temurin:17-jre-alpine` and attempts to install Node.js:
   ```dockerfile
   RUN apk add --no-cache nodejs npm curl
   COPY flyo-rescue-demo/agent /app/agent
   RUN cd /app/agent && npm install --production
   ```
   **Why this fails:** Playwright requires Chromium/WebKit browser binaries linked against standard GNU `glibc` and extensive desktop dynamic libraries (`libatk`, `libX11`, `libnss3`, `libasound2`). Alpine Linux uses `musl libc`. Running Playwright in this Alpine image results in immediate segmentation faults and binary execution failures.
2. **Missing ML Service in Docker Compose:** `docker-compose.yml` launches only `flyo-db` and `flyo-backend`. The FastAPI ML service (`ml/service`) is completely omitted. When Spring Boot tries to call `http://localhost:8000/predict`, the call fails with connection refused.
3. **Database Port & Credential Exposure:** PostgreSQL port `5432:5432` is bound directly to the public host interface with hardcoded credentials (`flyo_admin / flyo_secure_pass_2026`).
4. **FastAPI SQLite Storage Disconnect:** The ML service persists drift and monitoring metrics into a local `monitoring.db` SQLite file. In a clustered Kubernetes environment, each container instance maintains an isolated SQLite database, preventing shared observability.

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                          REMEDIATED INFRASTRUCTURE TOPOLOGY                            │
 │                                                                                        │
 │  INGRESS / REVERSE PROXY                                                               │
 │  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
 │  │ NGINX / Caddy Reverse Proxy (TLS 1.3 Terminus, Rate Limiting, WAF, Static Assets)│  │
 │  └───────────────────────────┬──────────────────────────────────┬───────────────────┘  │
 │                              │                                  │                      │
 │       ┌──────────────────────┴──────┐                           │                      │
 │       ▼ :8080                       ▼ :8000                     ▼ :8080                │
 │  ┌─────────────────────────┐   ┌─────────────────────────┐ ┌─────────────────────────┐│
 │  │ Spring Boot Backend     │   │ FastAPI ML Service      │ │ Background Outbox       ││
 │  │ (Eclipse Temurin Debian)│   │ (Python 3.11 Slim)      │ │ Worker Pod              ││
 │  │ State Machine & CADM    │   │ Inference & Monitoring  │ │ Saga Dispatcher         ││
 │  └────────────┬────────────┘   └────────────┬────────────┘ └────────────┬────────────┘│
 │               │                             │                           │             │
 │               └───────────────────────┐     │     ┌─────────────────────┘             │
 │                                       ▼     ▼     ▼                                   │
 │                       ┌─────────────────────────────────────────┐                     │
 │                       │ Managed PostgreSQL 15+ (Private Network)│                     │
 │                       │ HikariCP Connection Pool (Max 20/Node)  │                     │
 │                       └─────────────────────────────────────────┘                     │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Decoupled Multi-Service Container Strategy

Flyo splits monolithic responsibilities into three distinct, single-purpose container images:

### Service 1: Spring Boot Backend Engine (`flyo-backend`)
* **Base Image:** `eclipse-temurin:17-jre-jammy` (Debian-based).
* **Scope:** Houses the Spring Boot application, JPA repositories, CADM contracts, state machine, and REST APIs.
* **Removal of Node.js/Playwright:** The Node.js and Playwright runtimes are completely stripped from the backend container. All real rebooking executes via HTTP REST/SOAP connectors.

### Service 2: FastAPI Machine Learning & Monitoring Service (`flyo-ml`)
* **Base Image:** `python:3.11-slim-bookworm`.
* **Scope:** Houses the FastAPI server, XGBoost runtime, and drift detectors.
* **Database Migration:** Deprecates local SQLite in production. Connects to PostgreSQL using `psycopg2` or `asyncpg` to store predictions, feedback outcomes, and drift tables centrally.

### Service 3: Background Outbox & Saga Worker (`flyo-worker`)
* **Base Image:** Same JAR as `flyo-backend`, running with profile `--spring.profiles.active=worker`.
* **Scope:** Executes asynchronous outbox polling, external GDS dispatching, and compensation sagas without consuming web request threads.

---

## 3. Production Docker Compose Architecture

```yaml
version: '3.8'

services:
  reverse-proxy:
    image: caddy:2-alpine
    container_name: flyo-proxy
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./infra/Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
    depends_on:
      - flyo-backend
    networks:
      - flyo-internal

  flyo-db:
    image: postgres:15-alpine
    container_name: flyo-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB_FILE: /run/secrets/db_name
      POSTGRES_USER_FILE: /run/secrets/db_user
      POSTGRES_PASSWORD_FILE: /run/secrets/db_password
    secrets:
      - db_name
      - db_user
      - db_password
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - flyo-internal

  flyo-backend:
    build:
      context: .
      dockerfile: infra/docker/Dockerfile.backend
    container_name: flyo-backend
    restart: unless-stopped
    depends_on:
      flyo-db:
        condition: service_healthy
      flyo-ml:
        condition: service_healthy
    secrets:
      - db_password
      - jwt_secret
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://flyo-db:5432/flyodb
      SPRING_DATASOURCE_USERNAME: flyo_app
      SPRING_DATASOURCE_PASSWORD_FILE: /run/secrets/db_password
      FLYO_ML_SERVICE_URL: http://flyo-ml:8000
    networks:
      - flyo-internal

  flyo-ml:
    build:
      context: ./ml
      dockerfile: Dockerfile.ml
    container_name: flyo-ml
    restart: unless-stopped
    depends_on:
      flyo-db:
        condition: service_healthy
    secrets:
      - monitoring_api_key
    environment:
      DATABASE_URL: postgresql://flyo_app:${DB_PASS}@flyo-db:5432/flyodb
      FLYO_MONITORING_API_KEY_FILE: /run/secrets/monitoring_api_key
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8000/health/live"]
      interval: 10s
      timeout: 3s
      retries: 3
    networks:
      - flyo-internal

secrets:
  db_name:
    file: ./infra/secrets/db_name.txt
  db_user:
    file: ./infra/secrets/db_user.txt
  db_password:
    file: ./infra/secrets/db_password.txt
  jwt_secret:
    file: ./infra/secrets/jwt_secret.txt
  monitoring_api_key:
    file: ./infra/secrets/monitoring_key.txt

volumes:
  postgres_data:
  caddy_data:

networks:
  flyo-internal:
    internal: true
```

---

## 4. Kubernetes Resource Limits & High Availability

In enterprise Kubernetes clusters (EKS/GKE/OpenShift):
* **`flyo-backend` Deployment:** 
  - Replicas: 3 (Auto-scales on CPU > 70%).
  - Resources: `requests: {cpu: 500m, memory: 1Gi}`, `limits: {cpu: 2000m, memory: 2Gi}`.
  - JVM Configuration: `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0`.
  - Probes: Liveness `/actuator/health/liveness`, Readiness `/actuator/health/readiness`.
* **Database Tuning:** 
  - HikariCP pool tuned to `maximum-pool-size: 20`, `minimum-idle: 5`.
  - Automated nightly WAL archiving to AWS S3 with point-in-time recovery (PITR) up to 14 days.
