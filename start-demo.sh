#!/usr/bin/env bash
# ==============================================================================
# FLYO RESCUE — EXECUTIVE MVP DEMO LAUNCHER (STAGE 10B.1)
# ==============================================================================
# Starts backend (demo profile), ML microservice, verifies health gates,
# opens browser, and displays live walkthrough guide for hiring manager demo.
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
echo "  DEMO INFRASTRUCTURE SPECIFICATION:                                            "
echo "  - Active Profile: demo (Safe demo seeding & demo session auth enabled)        "
echo "  - Persistence: H2 In-Memory with Flyway V1-V10 automated schema migrations   "
echo "  - ML Microservice: FastAPI / Uvicorn port 8000 (Random Forest & GBM models)   "
echo "  - Execution Mode: MOCK_DEMO (Playwright automation against mock airline portal)"
echo "  - Treasury Mode: SIMULATED_VCC (Stripe Issuing Sandbox test instrument)      "
echo "  - 2FA Mode: SIMULATED DEMO CONVENIENCE (Walkthrough prefill; manual in prod)  "
echo "================================================================================"

# 1. Safely inspect and terminate existing Flyo processes on ports 8080 & 8000
safe_terminate_port() {
    local port="$1"
    local pids
    pids=$(lsof -ti :"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        for pid in $pids; do
            local cmd
            cmd=$(ps -p "$pid" -o comm= 2>/dev/null || true)
            echo "  Port ${port} occupied by PID ${pid} (${cmd})"
            if [[ "$cmd" =~ java|mvn|python|uvicorn|node ]]; then
                echo "  -> Gracefully terminating Flyo process (PID ${pid})..."
                kill "$pid" 2>/dev/null || true
                sleep 1
                if ps -p "$pid" >/dev/null 2>&1; then
                    echo "  -> Force terminating unresponsive process (PID ${pid})..."
                    kill -9 "$pid" 2>/dev/null || true
                fi
            else
                echo "  [ERROR] Port ${port} is occupied by non-Flyo process '${cmd}' (PID ${pid})."
                echo "  Aborting startup to prevent accidental termination of external services."
                echo "  Please free port ${port} manually before running start-demo.sh."
                exit 1
            fi
        done
    fi
}

echo "[1/4] Safely inspecting ports 8080 and 8000..."
safe_terminate_port 8080
safe_terminate_port 8000
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

# 3. Start Spring Boot Backend under explicit demo profile
echo "[3/4] Launching Spring Boot Backend with demo profile on port 8080..."
(cd "${ROOT_DIR}/flyo-rescue-demo/backend" && mvn spring-boot:run -Dspring-boot.run.profiles=demo > /tmp/flyo_backend.log 2>&1) &
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
    echo "  EXECUTIVE DEMO WALKTHROUGH SCRIPT (HONEST & RIGOROUS POSITIONING)            "
    echo "--------------------------------------------------------------------------------"
    echo "  STEP 1: Trip Monitor & Disruption Simulation"
    echo "    - Click 'Run Hero Automation ->' under Pre-Made Adoptive Scenario Templates."
    echo "    - Show the Disruption Summary: Flight AI-839 cancelled (Passenger Ananya Sharma)."
    echo ""
    echo "  STEP 2: Automated Impact & Deterministic Alternative Ranking"
    echo "    - Switch to 'Case Recovery' tab (Active badge)."
    echo "    - Point out: Passenger has critical onward connection DEL -> YYZ tomorrow morning."
    echo "    - Option O2 (FL301 via BOM) ranked Rank 1: preserves 7h 50m connection buffer (+₹4,200)."
    echo "    - Option O3 disqualified from Rank 1: 45m buffer violates minimum 120m safety rule."
    echo ""
    echo "  STEP 3: Operator Approval Gate & Corporate VCC Treasury Card"
    echo "    - Show the 2FA Security Gate: Click 'Request WhatsApp OTP' or check WhatsApp panel."
    echo "    - Note: Visibly labeled as [SIMULATED DEMO CONVENIENCE] for smooth local walkthrough."
    echo "    - Click 'Verify & Unlock Gate': Gate unlocks Option O2 for authorized checkout."
    echo "    - Show Corporate Virtual Card: Visibly labeled [SIMULATED VCC — STRIPE ISSUING SANDBOX]"
    echo "      with PCI masking (•••• •••• •••• 8821) and MCC 3000 airline category lock."
    echo ""
    echo "  STEP 4: Headless Playwright Automation & Demo PNR Issuance"
    echo "    - Click 'Execute Rebooking (Playwright)'."
    echo "    - In ~2s, Playwright navigates Flyo's mock airline portal, fills passenger details,"
    echo "      submits the payment form, captures 4 audit screenshots, and extracts demo PNR R7X2K9."
    echo "    - Note: Clearly labeled as [MOCK_DEMO] execution mode and simulated demo PNR."
    echo "    - Show WhatsApp mockup: Confirmed flight notice dispatched to passenger."
    echo ""
    echo "  STEP 5: ML Observability & Immutable Audit Trail"
    echo "    - Click 'Audit Logs' tab: Review immutable hash-chained event ledger."
    echo "    - Click 'ML Observability' tab: Dual models (flight delay forecast & connection risk)."
    echo "--------------------------------------------------------------------------------"
    echo "  Press Ctrl+C to stop services and exit."
    echo "================================================================================"

    # Trap Ctrl+C to cleanly kill processes
    trap 'echo ""; echo "Shutting down Flyo demo services..."; kill ${BACKEND_PID} 2>/dev/null || true; [ -n "${ML_PID}" ] && kill ${ML_PID} 2>/dev/null || true; exit 0' INT
    wait ${BACKEND_PID}
else
    echo "✖ Startup timed out. Check logs at /tmp/flyo_backend.log and /tmp/flyo_ml.log"
    kill ${BACKEND_PID} 2>/dev/null || true
    [ -n "${ML_PID}" ] && kill ${ML_PID} 2>/dev/null || true
    exit 1
fi
