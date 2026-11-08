# FLYO RESCUE — STAGE 9: PRODUCTION DEPLOYMENT GUIDE

## 1. Prerequisites
- **Java**: OpenJDK 17 or 21 LTS
- **Node.js**: Node 20+ LTS & npm 10+
- **Python**: Python 3.11+ (with virtualenv)
- **Database**: PostgreSQL 15+ (or H2 for standalone testing)

---

## 2. Environment Variables Specification

| Variable Name | Required | Default | Description |
| :--- | :--- | :--- | :--- |
| `SPRING_PROFILES_ACTIVE` | Yes | `prod` | Activates production profile. |
| `AMADEUS_CLIENT_ID` | Yes | - | Production Amadeus GDS Client ID. |
| `AMADEUS_CLIENT_SECRET` | Yes | - | Production Amadeus GDS Client Secret. |
| `STRIPE_SECRET_KEY` | Yes | - | Production Stripe Secret Key (PCI VCC Issuance). |
| `AVIATIONSTACK_API_KEY`| Yes | - | Live Aviation Radar access key. |
| `WHATSAPP_API_TOKEN` | Yes | - | Meta Cloud API bearer token. |
| `WHATSAPP_PHONE_NUMBER_ID`| Yes | - | Meta Cloud verified business phone number ID. |
| `WHATSAPP_APP_SECRET` | Yes | - | Meta App Secret for webhook HMAC signature verification. |
| `FLYO_INITIAL_ADMIN_USERNAME`| Optional | - | First-time production bootstrap admin username. |
| `FLYO_INITIAL_ADMIN_PASSWORD`| Optional | - | First-time production bootstrap admin password (min 12 chars). |
| `JWT_SECRET` | Yes | - | Minimum 32-character high-entropy signing key. |

---

## 3. Clean Empty-Database Startup Procedure

1. **Provision PostgreSQL Database**:
   ```sql
   CREATE DATABASE flyodb_prod;
   CREATE USER flyo_admin WITH ENCRYPTED PASSWORD '...';
   GRANT ALL PRIVILEGES ON DATABASE flyodb_prod TO flyo_admin;
   ```

2. **Execute Flyway Schema Migrations**:
   Flyway runs automatically on Spring Boot boot, applying migrations `V1__init_schema.sql` through `V9__tenants_and_security.sql`.

3. **Launch Backend Service**:
   ```bash
   cd flyo-rescue-demo/backend
   SPRING_PROFILES_ACTIVE=prod \
   FLYO_INITIAL_ADMIN_USERNAME=opsadmin \
   FLYO_INITIAL_ADMIN_PASSWORD=ProductionSuperPass2026! \
   java -jar target/rescue-backend-0.0.1-SNAPSHOT.jar
   ```

4. **Launch ML Intelligence Service**:
   ```bash
   cd ml
   source .venv/bin/activate
   uvicorn ml.service.main:app --host 0.0.0.0 --port 8000 --workers 4
   ```

5. **Verify Empty State**:
   Access `http://localhost:8080/`. The dashboard will show:
   - `0 Active` Monitored Trips
   - `0 Blocked`
   - `0 Rescued`
   - Provider Engine: `GDS / NDC CONNECTED`
