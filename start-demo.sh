#!/usr/bin/env bash
# ==============================================================================
# FLYO RESCUE — EXECUTIVE MVP DEMO LAUNCHER
# ==============================================================================
# Starts backend, ML microservice, verifies health gates, opens browser,
# and displays live walkthrough guide for hiring manager presentation.
# ==============================================================================

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${SCRIPT_DIR}"

echo "================================================================================"
echo "          FLYO RESCUE AGENT — EXECUTIVE MVP DEMO LAUNCHER                      "
echo "================================================================================"
echo "  Target: Flight Operations Autonomous Disruption Recovery Engine               "
echo "  Console: http://localhost:8080                                                "
echo "  ML Observability: http://localhost:8000/monitoring/health                     "
echo "================================================================================"

# 1. Kill any existing instances on ports 8080 & 8000
echo "[1/4] Checking ports 8080 and 8000..."
lsof -ti:8080 | xargs kill -9 2>/dev/null || true
lsof -ti:8000 | xargs kill -9 2>/dev/null || true
sleep 1

# 2. Start ML Microservice in background
echo "[2/4] Starting Flyo ML Predictive Service on port 8000..."
if [ -d "${ROOT_DIR}/ml/.venv" ]; then
    (cd "${ROOT_DIR}" && ./ml/.venv/bin/uvicorn ml.service.main:app --host 0.0.0.0 --port 8000 > /tmp/flyo_ml.log 2>&1) &
    ML_PID=$!
    echo "  -> ML Service started (PID: ${ML_PID})"
else
    echo "  -> Note: Python virtualenv not found; running with ML client graceful fallback."
fi

# 3. Start Spring Boot Backend
echo "[3/4] Launching Spring Boot Backend on port 8080..."
(cd "${ROOT_DIR}/flyo-rescue-demo/backend" && mvn spring-boot:run -Dspring-boot.run.profiles=default > /tmp/flyo_backend.log 2>&1) &
BACKEND_PID=$!
echo "  -> Backend process started (PID: ${BACKEND_PID}). Awaiting health readiness..."

# 4. Wait for health endpoint to become UP
MAX_ATTEMPTS=45
ATTEMPT=0
READY=false

while [ ${ATTEMPT} -lt ${MAX_ATTEMPTS} ]; do
    ATTEMPT=$((ATTEMPT + 1))
    STATUS=$(curl -s http://localhost:8080/actuator/health 2>/dev/null | grep -o '"status":"UP"' || true)
    if [ -n "${STATUS}" ]; then
        READY=true
        break
    fi
    printf "."
    sleep 2
done
echo ""

if [ "${READY}" = "true" ]; then
    echo "================================================================================"
    echo "  ✔ FLYO RESCUE PLATFORM IS LIVE & READY FOR YOUR HIRING MANAGER DEMO!          "
    echo "================================================================================"
    echo ""
    echo "  Opening Console: http://localhost:8080"
    if command -v open >/dev/null 2>&1; then
        open "http://localhost:8080"
    elif command -v xdg-open >/dev/null 2>&1; then
        xdg-open "http://localhost:8080"
    fi

    echo ""
    echo "--------------------------------------------------------------------------------"
    echo "  RECOMMENDED 5-MINUTE DEMO WALKTHROUGH SCRIPT FOR YOUR HIRING MANAGER         "
    echo "--------------------------------------------------------------------------------"
    echo "  STEP 1: Trip Monitor & Adoptive Presets"
    echo "    - Click 'Run Hero Automation ->' under Pre-Made Adoptive Scenario Templates."
    echo "    - Show the Disruption Summary Bar: 5 Monitored, 1 Blocked (AI-839 Ananya Sharma)."
    echo ""
    echo "  STEP 2: Automated Impact & Deterministic Ranking Engine"
    echo "    - Navigate to 'Case Recovery' tab (now showing Active badge)."
    echo "    - Point out: AI-839 cancelled. Ananya Sharma has onward flight to Toronto (YYZ)."
    echo "    - Highlight Option O2 ranked as Rank 1 (+₹4,200) because it preserves 7h 50m buffer."
    echo "    - Note: Option O3 is disqualified because 45m connection is below safe 120m threshold."
    echo ""
    echo "  STEP 3: 2FA Operator Approval & Corporate VCC Treasury Card"
    echo "    - Show the 2FA Gate: Click 'Request WhatsApp OTP' or look at the phone mockup."
    echo "    - Click 'Verify & Unlock Gate' (OTP automatically pre-fills from chat!)."
    echo "    - Show Corporate Virtual Card (Stripe Issuing) authorized for ₹4,200 with MCC 3000 lock."
    echo ""
    echo "  STEP 4: Headless Playwright Rebooking & Real Airline Confirmation"
    echo "    - Click 'Execute Rebooking (Playwright)'."
    echo "    - In 2 seconds, headless browser completes carrier booking and issues PNR R7X2K9."
    echo "    - Show the phone mockup on the right: WhatsApp confirms new itinerary with boarding pass!"
    echo ""
    echo "  STEP 5: ML Observability & Chronological Audit Ledger"
    echo "    - Click 'Audit Logs' tab: Show immutable timeline (disruption -> OTP -> VCC -> rebook)."
    echo "    - Click 'ML Observability' tab: Show real-time model monitoring, delay forecast & drift."
    echo "--------------------------------------------------------------------------------"
    echo "  Press Ctrl+C to stop services and exit."
    echo "================================================================================"

    # Trap Ctrl+C to cleanly kill processes
    trap 'echo ""; echo "Shutting down Flyo demo services..."; kill ${BACKEND_PID} 2>/dev/null || true; kill ${ML_PID} 2>/dev/null || true; exit 0' INT
    wait ${BACKEND_PID}
else
    echo "✖ Startup timed out. Check logs at /tmp/flyo_backend.log and /tmp/flyo_ml.log"
    kill ${BACKEND_PID} 2>/dev/null || true
    kill ${ML_PID} 2>/dev/null || true
    exit 1
fi
