# STAGE 6 — PRODUCTION DEPLOYMENT & INFRASTRUCTURE ARCHITECTURE

## 1. Executive Summary
Flyo Stage 6 introduces a fully reproducible, containerized production deployment topology orchestrated via Docker Compose, multi-stage Docker builds, PostgreSQL 16 persistence, Redis 7 caching, automated secret injection, health/readiness checks, database backup/restore scripts, and a CI verification pipeline.

## 2. Infrastructure Topology (`docker-compose.prod.yml`)
The production architecture separates services into an internal backplane network and an edge ingress network:
- **`flyo-postgres`**: PostgreSQL 16 Alpine with custom memory tunings (`shared_buffers=256MB`, `work_mem=16MB`), persistent volume mounting (`flyo_pgdata`), and `pg_isready` healthcheck probes.
- **`flyo-redis`**: Redis 7 Alpine with mandatory password authentication (`requirepass`), in-memory rate limiting, and 128MB cache limits.
- **`flyo-ml`**: Python 3.11 lightweight container running FastAPI, XGBoost delay regressor, calibrated cancellation classifier, and monitoring endpoints (`/health/ready`, `/predict/flight`).
- **`flyo-backend`**: Multi-stage Spring Boot 3.3.4 container running under OpenJDK 17/21 JRE, with fail-closed production security validation, Flyway auto-migration, HikariCP connection pool, and Prometheus metrics export.

## 3. Configuration & Secret Management
- `.env.production.example` provides the canonical template for all production environment variables without committing credentials to Git.
- **Environment Validation Script (`scripts/validate_prod_env.sh`)**: Validates that required environment variables (`DB_URL`, `DB_PASSWORD`, `JWT_SECRET`, `AMADEUS_CLIENT_ID`, `META_APP_SECRET`) are populated with non-default, high-entropy secrets before allowing container startup.
- **Database Backup & Disaster Recovery (`scripts/backup_restore_db.sh`)**: Provides automated PostgreSQL dump (`pg_dump -Fc`) and restore (`pg_restore --clean`) procedures with timestamped backup archives and integrity checks.

## 4. Continuous Integration Matrix (`.github/workflows/ci.yml`)
Automated multi-job GitHub Actions pipeline:
- **Backend Job**: Sets up Java 17 & PostgreSQL service container, runs Flyway migrations, executes all 242 JUnit 5 tests, runs checkstyle/linter.
- **Frontend Job**: Sets up Node.js 20, runs `npm test`, validates minified production bundle build.
- **ML Job**: Sets up Python 3.11, loads multi-season artifacts, runs all 26 unittest suites.
- **Security Audit Job**: Scans dependencies and checks for leaked secrets or hardcoded test keys.
