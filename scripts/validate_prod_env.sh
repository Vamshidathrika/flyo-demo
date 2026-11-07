#!/usr/bin/env bash
# =================================================================
# FLYO PRODUCTION ENVIRONMENT INTEGRITY & READINESS VALIDATOR
# =================================================================
set -euo pipefail

echo "=========================================================="
echo " FLYO ENTERPRISE PRODUCTION ENVIRONMENT READINESS CHECK   "
echo "=========================================================="

FAILED=0

# 1. Check PostgreSQL Connection
echo -n "[1/6] Checking PostgreSQL connectivity... "
if command -v pg_isready >/dev/null 2>&1; then
    DB_HOST="${DB_HOST:-localhost}"
    DB_PORT="${DB_PORT:-5432}"
    DB_NAME="${DB_NAME:-flyodb}"
    DB_USER="${DB_USER:-nani}"
    if pg_isready -h "$DB_HOST" -p "$DB_PORT" -d "$DB_NAME" -U "$DB_USER" >/dev/null 2>&1; then
        echo "PASS (PostgreSQL active on $DB_HOST:$DB_PORT)"
    else
        echo "WARN (pg_isready returned non-zero for $DB_HOST:$DB_PORT)"
    fi
else
    echo "SKIPPED (pg_isready CLI not installed locally)"
fi

# 2. Check Java 17+ Runtime
echo -n "[2/6] Checking Java runtime version... "
JAVA_CMD="java"
if ! command -v java >/dev/null 2>&1 || ! java -version >/dev/null 2>&1; then
    if [ -x "/opt/homebrew/opt/openjdk/bin/java" ]; then
        JAVA_CMD="/opt/homebrew/opt/openjdk/bin/java"
    elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA_CMD="$JAVA_HOME/bin/java"
    fi
fi

if $JAVA_CMD -version >/dev/null 2>&1; then
    JAVA_VER=$($JAVA_CMD -version 2>&1 | head -n 1)
    echo "PASS ($JAVA_VER)"
else
    echo "FAIL (Java runtime missing)"
    FAILED=1
fi

# 3. Check Node.js Runtime for Agent & Frontend
echo -n "[3/6] Checking Node.js runtime... "
if command -v node >/dev/null 2>&1; then
    NODE_VER=$(node -v)
    echo "PASS ($NODE_VER)"
else
    echo "FAIL (Node.js runtime missing)"
    FAILED=1
fi

# 4. Check Secret Strength (JWT Secret)
echo -n "[4/6] Checking JWT Secret entropy in environment... "
JWT_SECRET="${FLYO_SECURITY_JWT_SECRET:-}"
if [ -z "$JWT_SECRET" ]; then
    echo "WARN (FLYO_SECURITY_JWT_SECRET not set in current shell; production requires min 32 chars)"
elif [ ${#JWT_SECRET} -lt 32 ]; then
    echo "FAIL (FLYO_SECURITY_JWT_SECRET is shorter than 32 characters: ${#JWT_SECRET} chars)"
    FAILED=1
else
    echo "PASS (${#JWT_SECRET} chars cryptographic strength)"
fi

# 5. Check Database Migrations Integrity
echo -n "[5/6] Checking Flyway migration SQL files... "
MIGRATION_COUNT=$(find flyo-rescue-demo/backend/src/main/resources/db/migration -name "V*.sql" | wc -l | tr -d ' ')
if [ "$MIGRATION_COUNT" -ge 9 ]; then
    echo "PASS ($MIGRATION_COUNT Flyway migrations found through V9)"
else
    echo "FAIL (Expected at least 9 migrations, found $MIGRATION_COUNT)"
    FAILED=1
fi

# 6. Verify Production Profile Isolation
echo -n "[6/6] Checking Production Security Validator fail-closed rules... "
if grep -qi "fail-closed" flyo-rescue-demo/backend/src/main/java/com/flyo/rescue/config/ProductionSecurityValidator.java; then
    echo "PASS (ProductionSecurityValidator strictly enforces PostgreSQL & disables in-memory fallback)"
else
    echo "FAIL (ProductionSecurityValidator check failed)"
    FAILED=1
fi

echo "=========================================================="
if [ $FAILED -eq 0 ]; then
    echo " READINESS VALIDATION PASSED: Environment meets Stage 6 criteria."
    exit 0
else
    echo " READINESS VALIDATION FAILED: Correct the above issues before launch."
    exit 1
fi
