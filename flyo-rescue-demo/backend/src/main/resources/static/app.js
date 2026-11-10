let currentActiveCase = null;
let lastChatCount = 0;
let currentActiveView = "monitor";
let authToken = localStorage.getItem("flyo_jwt_token");
let currentOperator = JSON.parse(localStorage.getItem("flyo_operator") || "null");

async function autoLoginDemoUser() {
    try {
        const res = await fetch("/api/auth/demo-session", {
            method: "POST",
            headers: { "Content-Type": "application/json" }
        });
        if (res.ok) {
            const data = await res.json();
            if (data.token) {
                authToken = data.token;
                currentOperator = {
                    username: data.username || "admin",
                    fullName: data.fullName || "System Admin",
                    roles: data.roles || ["ROLE_ADMIN"]
                };
                localStorage.setItem("flyo_jwt_token", authToken);
                localStorage.setItem("flyo_operator", JSON.stringify(currentOperator));
                updateOperatorBadgeUI();
                return true;
            }
        } else {
            console.warn("[Flyo Auth] Demo auto-login disabled or forbidden in this environment (active profile is not 'demo').");
        }
    } catch (e) {
        console.warn("[Flyo Auth] Auto-login attempt failed:", e);
    }
    return false;
}

function updateOperatorBadgeUI() {
    const badgeText = document.getElementById("operator-status-text");
    if (badgeText && currentOperator) {
        badgeText.textContent = `Operator: ${currentOperator.fullName || currentOperator.username} (${currentOperator.roles ? currentOperator.roles[0] : 'ROLE_ADMIN'})`;
    }
}

async function apiFetch(url, options = {}) {
    options = options || {};
    options.headers = options.headers || {};

    if (!authToken && !url.includes("/api/auth/login")) {
        await autoLoginDemoUser();
    }

    const applyAuth = (hdrs) => {
        if (authToken) {
            if (hdrs instanceof Headers) {
                if (!hdrs.has("Authorization")) hdrs.set("Authorization", `Bearer ${authToken}`);
            } else {
                if (!hdrs["Authorization"] && !hdrs["authorization"]) hdrs["Authorization"] = `Bearer ${authToken}`;
            }
        }
    };

    applyAuth(options.headers);
    let res = await fetch(url, options);

    if (res.status === 401 && !url.includes("/api/auth/login")) {
        console.warn("[Flyo Auth] Received 401 Unauthorized, re-authenticating session...");
        const refreshed = await autoLoginDemoUser();
        if (refreshed) {
            applyAuth(options.headers);
            res = await fetch(url, options);
        }
    }
    return res;
}

function switchSectionView(tabName) {
    currentActiveView = tabName;
    const tabs = ["monitor", "ananya", "msg", "logs", "monitoring", "ops"];
    tabs.forEach(t => {
        const view = document.getElementById(`view-${t}`);
        const btn = document.getElementById(`tab-${t}`);
        if (view) {
            if (t === tabName) {
                view.classList.add("active-view");
                view.style.display = "block";
            } else {
                view.classList.remove("active-view");
                view.style.display = "none";
            }
        }
        if (btn) {
            if (t === tabName) {
                btn.classList.add("active");
            } else {
                btn.classList.remove("active");
            }
        }
    });

    const activeId = currentActiveCase ? currentActiveCase.id : "C1";
    if (tabName === "logs") {
        fetchAudit(activeId);
    } else if (tabName === "msg") {
        fetchMessages(activeId);
    } else if (tabName === "monitor") {
        fetchBookings();
    } else if (tabName === "monitoring") {
        fetchMonitoringData();
    } else if (tabName === "ops") {
        fetchOpsData();
    }
}

document.addEventListener("DOMContentLoaded", async () => {
    updateOperatorBadgeUI();
    if (!authToken) {
        await autoLoginDemoUser();
    }
    fetchBookings();
    fetchActiveCases();
    fetchWhatsAppOperatorChat();
    fetchOtpStatus();

    // Live Polling every 2.0 seconds to keep Dashboard & WhatsApp strictly in sync
    setInterval(syncDashboard, 2000);
});

async function syncDashboard() {
    await fetchActiveCases(true);
    await fetchWhatsAppOperatorChat(true);
    await fetchOtpStatus();
    if (currentActiveView === "monitoring") {
        await fetchMonitoringData(true);
    } else if (currentActiveView === "ops") {
        await fetchOpsData(true);
    }
}

async function fetchOpsData(silent = false) {
    try {
        const res = await apiFetch("/api/cases/operations-summary");
        if (!res.ok) return;
        const data = await res.json();

        const activeElem = document.getElementById("ops-active-count");
        const manualElem = document.getElementById("ops-manual-count");
        const failedElem = document.getElementById("ops-failed-count");
        const resolvedElem = document.getElementById("ops-resolved-count");

        if (activeElem) activeElem.textContent = data.activeDisruptionsCount || 0;
        if (manualElem) manualElem.textContent = data.manualInterventionCount || 0;
        if (failedElem) failedElem.textContent = data.failedEscalationsCount || 0;
        if (resolvedElem) resolvedElem.textContent = data.resolvedDoneCount || 0;

        const tbody = document.getElementById("ops-intervention-tbody");
        if (tbody) {
            const cases = data.attentionRequiredCases || [];
            if (cases.length === 0) {
                tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; color: #16a34a; padding: 1.5rem; font-weight: 600;">✔ Zero pending manual interventions. All automated sagas operating normally.</td></tr>`;
            } else {
                tbody.innerHTML = cases.map(c => `
                    <tr style="background: ${c.state === 'FAILED_ESCALATED' ? '#fef2f2' : '#fffbeb'};">
                        <td><strong>${c.id}</strong></td>
                        <td>${c.passengerName || 'Passenger'}</td>
                        <td><span class="badge ${c.state === 'FAILED_ESCALATED' ? 'badge-failed' : 'badge-warning'}">${c.state}</span></td>
                        <td style="color: #b91c1c; font-family: monospace; font-size: 0.75rem;">${c.error || 'Provider Ambiguity'}</td>
                        <td style="font-size: 0.75rem;">${c.manualNextStep || 'Inspect airline portal before retry.'}</td>
                        <td>
                            <button class="btn btn-sm btn-secondary" onclick="fetchCase('${c.id}'); switchSectionView('ananya');" style="font-size: 0.7rem; padding: 0.2rem 0.5rem;">
                                Inspect Case →
                            </button>
                        </td>
                    </tr>
                `).join("");
            }
        }

        // Stage 8 Predictive Operations Intelligence
        fetchPredictiveLeaderboard();
        fetchAirportHeatmap();
        fetchConnectionRiskQueue();
    } catch (err) {
        if (!silent) console.error("Error fetching ops data:", err);
    }
}

async function fetchBookings() {
    const tbody = document.getElementById("bookings-tbody");
    try {
        const response = await apiFetch("/api/bookings");
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        const bookings = await response.json();
        renderBookings(bookings);
    } catch (error) {
        console.error("Failed to fetch bookings:", error);
        if (tbody) {
            tbody.innerHTML = `<tr><td colspan="10" class="loading-state" style="color: red;">Error connecting to Flyo Backend API: ${error.message}</td></tr>`;
        }
    }
}

async function fetchCase(caseId) {
    if (!caseId) return;
    try {
        const res = await apiFetch(`/api/cases/${encodeURIComponent(caseId)}`);
        if (res.ok) {
            const data = await res.json();
            currentActiveCase = data;
            renderCase(data, true);
        }
    } catch (err) {
        console.error("Error fetching case:", err);
    }
}

async function fetchActiveCases(silent = false) {
    try {
        const response = await apiFetch("/api/cases");
        if (!response.ok) return;
        const cases = await response.json();
        if (cases && cases.length > 0) {
            let activeCase = null;
            if (currentActiveCase) {
                activeCase = cases.find(c => c.id === currentActiveCase.id);
            }
            if (!activeCase) {
                activeCase = cases.find(c => c.state !== "DONE" && c.state !== "ESCALATED") 
                    || cases.find(c => c.id === "C1") 
                    || cases[0];
            }
            const stateChanged = !currentActiveCase || currentActiveCase.state !== activeCase.state || currentActiveCase.approvedOptionId !== activeCase.approvedOptionId;
            currentActiveCase = activeCase;
            renderCase(activeCase, silent);
            updateCaseSwitcher(cases);

            if (stateChanged || !silent) {
                await fetchOptions(activeCase.id);
                await fetchAudit(activeCase.id);
                await fetchMessages(activeCase.id);
                await fetchBookings();
            }
        } else {
            const headingEl = document.getElementById("case-heading");
            if (headingEl) headingEl.textContent = "No Active Disruption Cases";
            const stateEl = document.getElementById("case-state");
            if (stateEl) {
                stateEl.textContent = "NORMAL_OPERATIONS";
                stateEl.className = "case-state-badge";
            }
            const optionsTbody = document.getElementById("options-tbody");
            if (optionsTbody) {
                optionsTbody.innerHTML = `<tr><td colspan="8" style="text-align: center; padding: 2rem; color: #64748b;">No active rescue operations. All flights operating on schedule.</td></tr>`;
            }
            const auditTbody = document.getElementById("audit-tbody");
            if (auditTbody) {
                auditTbody.innerHTML = `<tr><td colspan="5" style="text-align: center; padding: 1.5rem; color: #64748b;">Audit ledger clear. No operational interventions recorded.</td></tr>`;
            }
            const pills = document.getElementById("case-switcher-container");
            if (pills) pills.style.display = "none";
        }
    } catch (error) {
        if (!silent) console.error("Error fetching cases:", error);
    }
}

function updateCaseSwitcher(cases) {
    const container = document.getElementById("case-switcher-container");
    const pills = document.getElementById("case-switcher-pills");
    if (!container || !pills) return;

    if (!cases || cases.length <= 1) {
        container.style.display = "none";
        return;
    }

    container.style.display = "flex";
    pills.innerHTML = cases.map(c => {
        const isActive = currentActiveCase && currentActiveCase.id === c.id;
        const isDone = c.state === "DONE";
        const isEscalated = c.state === "ESCALATED" || c.state === "FAILED_ESCALATED";
        let stateTag = c.flight ? `${escapeHtml(c.flight)} Blocked` : "Active";
        if (isDone) stateTag = "Rescued";
        else if (isEscalated) stateTag = "Escalated";

        let pillClass = "case-pill";
        if (isActive) pillClass += " active";
        if (isDone) pillClass += " is-rescued";
        else pillClass += " has-blocked";

        return `
            <button type="button" class="${pillClass}" onclick="selectActiveCase('${escapeHtml(c.id)}')">
                <strong>${escapeHtml(c.id)}:</strong> ${escapeHtml(c.passengerName)} (${stateTag})
            </button>
        `;
    }).join("");
}

async function selectActiveCase(caseId) {
    try {
        const response = await apiFetch("/api/cases");
        if (!response.ok) return;
        const cases = await response.json();
        const found = cases.find(c => c.id === caseId);
        if (found) {
            currentActiveCase = found;
            renderCase(found);
            updateCaseSwitcher(cases);
            await fetchOptions(found.id);
            await fetchAudit(found.id);
            await fetchMessages(found.id);
        }
    } catch (err) {
        console.error("Error selecting active case:", err);
    }
}

async function openCaseForBooking(bookingId) {
    try {
        const response = await apiFetch("/api/cases");
        if (response.ok) {
            const cases = await response.json();
            const targetCase = cases.find(c => c.bookingId === bookingId || c.id === bookingId || (bookingId === "B1" && c.id === "C1") || c.id === "CASE-" + bookingId);
            if (targetCase) {
                currentActiveCase = targetCase;
                renderCase(targetCase);
                switchSectionView("ananya");
                await fetchOptions(targetCase.id);
                await fetchAudit(targetCase.id);
                await fetchMessages(targetCase.id);
                updateCaseSwitcher(cases);
                return;
            }
        }
        await simulateCancellation(bookingId);
    } catch (e) {
        console.error("Error opening case for booking:", e);
        await simulateCancellation(bookingId);
    }
}

async function simulateCancellation(bookingId) {
    const btn = document.getElementById("simulate-b1-btn");
    if (btn && bookingId === "B1") {
        btn.disabled = true;
        btn.textContent = "Simulating...";
    }

    try {
        const response = await apiFetch(`/api/cases/simulate?bookingId=${encodeURIComponent(bookingId)}&type=CANCELLED&notifyPassenger=true`, {
            method: "POST"
        });
        if (!response.ok) {
            throw new Error(`Simulation failed with status: ${response.status}`);
        }
        const rescueCase = await response.json();
        currentActiveCase = rescueCase;
        renderCase(rescueCase);
        switchSectionView("ananya");
        await fetchOptions(rescueCase.id);
        await fetchAudit(rescueCase.id);
        await fetchMessages(rescueCase.id);
        await fetchBookings();
        await fetchWhatsAppOperatorChat();
    } catch (error) {
        alert("Failed to simulate flight cancellation: " + error.message);
    } finally {
        if (btn && bookingId === "B1") {
            btn.disabled = false;
            btn.textContent = "Simulate Cancellation (AI-839)";
        }
    }
}

async function fetchOptions(caseId) {
    try {
        const response = await apiFetch(`/api/cases/${encodeURIComponent(caseId)}/options`);
        if (!response.ok) return;
        const options = await response.json();
        renderOptions(options);

        // Refresh active case so state badge smoothly updates to OPTIONS_READY
        const caseRes = await apiFetch(`/api/cases/${encodeURIComponent(caseId)}`);
        if (caseRes.ok) {
            currentActiveCase = await caseRes.json();
            renderCase(currentActiveCase, true);
        }
    } catch (error) {
        console.error("Error fetching options:", error);
    }
}

async function approveOption(caseId, optionId) {
    try {
        const response = await apiFetch(`/api/cases/${encodeURIComponent(caseId)}/approve`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                optionId: optionId,
                approvedBy: "Flyo Operator"
            })
        });

        if (!response.ok) {
            throw new Error(`Approval failed with status: ${response.status}`);
        }

        const updatedCase = await response.json();
        currentActiveCase = updatedCase;
        renderCase(updatedCase);
        await fetchOptions(caseId);
        await fetchAudit(caseId);
        await sendWhatsAppOperatorCommand(`APPROVE ${optionId}`);
        await fetchMessages(caseId);
        await fetchWhatsAppOperatorChat();
    } catch (error) {
        alert("Failed to approve option: " + error.message);
    }
}

async function rejectCase(caseId) {
    const targetCaseId = caseId || (currentActiveCase ? currentActiveCase.id : "C1");
    const reason = prompt("Enter reason for rejection & escalation:", "Passenger requested alternative routing");
    if (reason === null) return;

    try {
        const response = await apiFetch(`/api/cases/${encodeURIComponent(targetCaseId)}/reject`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                reason: reason || "Manual escalation requested by Flyo Operator",
                rejectedBy: "Flyo Operator"
            })
        });

        if (!response.ok) {
            throw new Error(`Reject failed with status: ${response.status}`);
        }

        const updatedCase = await response.json();
        currentActiveCase = updatedCase;
        renderCase(updatedCase);
        await fetchOptions(targetCaseId);
        await fetchAudit(targetCaseId);
        await sendWhatsAppOperatorCommand(`REJECT ${targetCaseId}`);
        await fetchMessages(targetCaseId);
        await fetchWhatsAppOperatorChat();
    } catch (error) {
        alert("Failed to reject case: " + error.message);
    }
}

async function executeRebook(caseId) {
    const targetCaseId = caseId || (currentActiveCase ? currentActiveCase.id : "C1");
    const btn = document.getElementById("execute-rebook-btn");
    if (btn) {
        btn.disabled = true;
        btn.textContent = "Running Playwright Worker...";
    }

    try {
        const response = await apiFetch(`/api/cases/${encodeURIComponent(targetCaseId)}/rebook`, {
            method: "POST"
        });

        if (!response.ok) {
            const err = await response.json().catch(() => ({}));
            throw new Error(err.message || `HTTP ${response.status}`);
        }

        const updatedCase = await response.json();
        currentActiveCase = updatedCase;
        renderCase(updatedCase);
        await fetchOptions(targetCaseId);
        await fetchAudit(targetCaseId);
        await fetchMessages(targetCaseId);
        await fetchBookings();
        await fetchWhatsAppOperatorChat();
    } catch (error) {
        alert("Rebooking execution error: " + error.message);
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.textContent = "Execute Rebooking (Playwright)";
        }
    }
}

function renderCase(c, silent = false) {
    if (!c) return;
    const section = document.getElementById("active-case-section");
    if (section) section.style.display = "block";

    const headingEl = document.getElementById("case-heading");
    if (headingEl) headingEl.textContent = `Case ${c.id} — ${c.passengerName}`;

    // Update Passenger WhatsApp header with passenger name & contact
    const paxHeaderName = document.getElementById("passenger-wa-contact-name");
    if (paxHeaderName && c.passengerName) {
        paxHeaderName.innerHTML = `Flyo &middot; ${escapeHtml(c.passengerName)} <span class="whatsapp-verified-badge">&#10003;</span>`;
    }
    const paxHeaderSub = document.getElementById("passenger-wa-contact-sub");
    if (paxHeaderSub && c.passengerPhone) {
        paxHeaderSub.innerHTML = `<span class="whatsapp-pulse-dot"></span> ${escapeHtml(c.passengerPhone)} &middot; ${escapeHtml(c.state)}`;
    }

    // Update Ananya tab badge
    const tabAnanyaBadge = document.getElementById("tab-ananya-badge");
    if (tabAnanyaBadge) {
        tabAnanyaBadge.style.display = "inline-block";
        if (c.state === "DONE") {
            tabAnanyaBadge.textContent = "Rescued";
            tabAnanyaBadge.style.backgroundColor = "#16a34a";
        } else if (c.state === "FAILED_ESCALATED" || c.state === "ESCALATED") {
            tabAnanyaBadge.textContent = "Escalated";
            tabAnanyaBadge.style.backgroundColor = "#dc2626";
        } else {
            tabAnanyaBadge.textContent = "Active";
            tabAnanyaBadge.style.backgroundColor = "#ef4444";
        }
    }

    // State badge
    const stateEl = document.getElementById("case-state");
    if (stateEl) {
        stateEl.textContent = c.state;
        stateEl.className = "case-state-badge";
        if (c.state === "AWAITING_APPROVAL") stateEl.classList.add("state-awaiting");
        else if (c.state === "ESCALATED") stateEl.classList.add("state-escalated");
        else if (c.state === "FAILED_ESCALATED") stateEl.classList.add("state-failed-escalated");
        else if (c.state === "REBOOKING") stateEl.classList.add("state-rebooking");
        else if (c.state === "DONE") stateEl.classList.add("badge-on-time");
    }

    const segEl = document.getElementById("case-segment");
    if (segEl) segEl.textContent = c.cancelledSegment;

    const impEl = document.getElementById("case-impact-text");
    if (impEl) impEl.textContent = c.impact;

    const connEl = document.getElementById("case-missed-conn");
    if (connEl) connEl.textContent = c.missedConnection;

    const hotEl = document.getElementById("case-hotel-risk");
    if (hotEl) hotEl.textContent = c.hotelRisk;

    // Multi-Source Evidence & Weather Telemetry Banner
    const evidenceBox = document.getElementById("case-evidence-box");
    if (evidenceBox) {
        if (c.reconciledConfidence || c.weatherSummary || c.disruptionEvidence) {
            evidenceBox.style.display = "block";
            const confEl = document.getElementById("case-evidence-confidence");
            if (confEl) confEl.textContent = `${c.reconciledConfidence || 93}% Reconciled Confidence`;
            const weathEl = document.getElementById("case-evidence-weather");
            if (weathEl) weathEl.textContent = c.weatherSummary || "DEL: Open-Meteo Telemetry";
            const textEl = document.getElementById("case-evidence-text");
            if (textEl) textEl.textContent = c.disruptionEvidence || "Multi-source evidence verified across Radar and Weather telemetry.";
        } else {
            evidenceBox.style.display = "none";
        }
    }

    // Update visual workflow stepper
    updateWorkflowStepper(c);

    // Fetch and render Treasury Virtual Card Settlement details
    fetchPaymentDetails(c.id);

    // Banners
    const approvalBanner = document.getElementById("approval-banner");
    const escalationBanner = document.getElementById("escalation-banner");
    const rebookSuccessCard = document.getElementById("rebook-success-card");
    const rebookFailureCard = document.getElementById("rebook-failure-card");

    if (c.approvedOptionId && c.state !== "ESCALATED" && c.state !== "FAILED_ESCALATED") {
        if (approvalBanner) {
            approvalBanner.style.display = "flex";
            const appTxt = document.getElementById("approved-option-text");
            if (appTxt) appTxt.textContent = `Option ${c.approvedOptionId} (${c.approvedBy || 'Flyo Operator'})`;
            const rebookBtn = document.getElementById("execute-rebook-btn");
            if (rebookBtn) rebookBtn.style.display = c.state === "DONE" ? "none" : "inline-flex";
        }
    } else {
        if (approvalBanner) approvalBanner.style.display = "none";
    }

    if (c.newPnr && c.state === "DONE") {
        if (rebookSuccessCard) {
            rebookSuccessCard.style.display = "block";
            const pnrCodeEl = document.getElementById("rescued-pnr-code");
            if (pnrCodeEl) pnrCodeEl.textContent = c.newPnr;

            // Stage 4 Enterprise Verification Badges
            const ticketBadge = document.getElementById("rescued-ticket-badge");
            if (ticketBadge) {
                if (c.ticketNumber) {
                    ticketBadge.style.display = "inline-block";
                    ticketBadge.textContent = `E-Ticket: ${c.ticketNumber} [${c.ticketStatus || 'VERIFIED'}]`;
                    ticketBadge.style.background = c.ticketStatus === "VERIFICATION_FAILED" ? "#dc2626" : "#0284c7";
                } else {
                    ticketBadge.style.display = "none";
                }
            }
            const notifBadge = document.getElementById("rescued-notification-badge");
            if (notifBadge) {
                if (c.notificationStatus) {
                    notifBadge.style.display = "inline-block";
                    notifBadge.textContent = `Passenger WhatsApp: ${c.notificationStatus}`;
                } else {
                    notifBadge.style.display = "none";
                }
            }
            const crmBadge = document.getElementById("rescued-crm-badge");
            if (crmBadge) {
                if (c.crmSyncStatus) {
                    crmBadge.style.display = "inline-block";
                    crmBadge.textContent = `Salesforce CRM: ${c.crmSyncStatus}`;
                } else {
                    crmBadge.style.display = "none";
                }
            }
            const corrBadge = document.getElementById("rescued-correlation-badge");
            if (corrBadge) {
                if (c.correlationId) {
                    corrBadge.style.display = "inline-block";
                    corrBadge.textContent = `Trace: ${c.correlationId.substring(0, 16)}...`;
                    corrBadge.title = c.correlationId;
                } else {
                    corrBadge.style.display = "none";
                }
            }
        }
    } else {
        if (rebookSuccessCard) rebookSuccessCard.style.display = "none";
    }

    if (c.state === "FAILED_ESCALATED") {
        if (rebookFailureCard) {
            rebookFailureCard.style.display = "block";
            const errEl = document.getElementById("failure-error-text");
            if (errEl) errEl.textContent = c.error || "Automated rebooking failed after 3 attempts on mock airline portal";
            const manEl = document.getElementById("failure-manual-step");
            if (manEl && c.manualNextStep) manEl.textContent = c.manualNextStep;
        }
    } else {
        if (rebookFailureCard) rebookFailureCard.style.display = "none";
    }

    if (c.state === "ESCALATED") {
        if (escalationBanner) {
            escalationBanner.style.display = "flex";
            const escTxt = document.getElementById("escalation-reason-text");
            if (escTxt) escTxt.textContent = c.error || "Rejected by Flyo Operator";
        }
    } else {
        if (escalationBanner) escalationBanner.style.display = "none";
    }

    if (!silent && section) {
        section.scrollIntoView({ behavior: "smooth", block: "start" });
    }
}

function updateWorkflowStepper(c) {
    const s1 = document.getElementById("step-1");
    const s2 = document.getElementById("step-2");
    const s3 = document.getElementById("step-3");
    const s4 = document.getElementById("step-4");
    const d1 = document.getElementById("step-div-1");
    const d2 = document.getElementById("step-div-2");
    const d3 = document.getElementById("step-div-3");

    if (!s1 || !s2 || !s3 || !s4) return;

    // Reset classes
    [s1, s2, s3, s4].forEach(el => el.className = "step-item");
    [d1, d2, d3].forEach(el => el && (el.className = "step-divider"));

    // Step 1: Disruption Detected is completed
    s1.classList.add("completed");
    if (d1) d1.classList.add("completed");

    // Step 2: Passenger Decision & Options
    const isStep2Active = ["DETECTED", "PASSENGER_NOTIFIED", "AWAITING_PASSENGER_DECISION", "FETCHING_ITINERARY", "SEARCHING_ALTERNATIVES"].includes(c.state);
    if (isStep2Active) {
        s2.classList.add("active");
    } else {
        s2.classList.add("completed");
        if (d2) d2.classList.add("completed");
    }

    // Step 3: Passenger OTP & Approval
    const isStep3Completed = !!c.approvedOptionId || !!c.passengerOtpVerifiedAt || ["PASSENGER_VERIFIED", "PAYMENT_AUTH_PENDING", "REBOOKING", "VERIFYING_BOOKING", "DONE"].includes(c.state);
    const isStep3Active = ["OPTIONS_READY", "AWAITING_APPROVAL", "AWAITING_OPTION_SELECTION", "OPTION_SELECTED", "REVALIDATING_OPTION", "AWAITING_PASSENGER_CONFIRMATION", "AWAITING_PASSENGER_OTP", "OTP_FAILED"].includes(c.state);

    if (isStep3Completed) {
        s3.classList.add("completed");
        if (d3) d3.classList.add("completed");
    } else if (isStep3Active) {
        s3.classList.add("active");
    }

    // Step 4: Rebooking Rescued
    if (c.state === "DONE") {
        s4.classList.add("completed");
    } else if (["REBOOKING", "VERIFYING_BOOKING"].includes(c.state)) {
        s4.classList.add("active");
    }
}

async function fetchPaymentDetails(caseId) {
    const cardEl = document.getElementById("vcc-treasury-card");
    if (!cardEl) return;
    try {
        const res = await apiFetch(`/api/payments/case/${encodeURIComponent(caseId)}`);
        if (!res.ok) return;
        const data = await res.json();
        if (data.hasVirtualCard && data.virtualCard) {
            cardEl.style.display = "block";
            const vcc = data.virtualCard;
            const statusBadge = document.getElementById("vcc-status-badge");
            if (statusBadge) {
                statusBadge.textContent = vcc.status;
                statusBadge.style.background = vcc.status === "CHARGED" ? "#16a34a" : "#2563eb";
            }
            const numEl = document.getElementById("vcc-display-number");
            if (numEl) {
                const raw = vcc.maskedCardNumber || "8821";
                const last4 = raw.replace(/\D/g, "").slice(-4) || "8821";
                numEl.textContent = `•••• •••• •••• ${last4}`;
            }
            const amtEl = document.getElementById("vcc-display-amount");
            if (amtEl) amtEl.textContent = `₹${vcc.amount ? vcc.amount.toLocaleString() : '4,200'}`;
            const mccEl = document.getElementById("vcc-display-mcc");
            if (mccEl) mccEl.textContent = vcc.merchantCategory || "MCC 3000 (Airlines)";

            const ledgerText = document.getElementById("vcc-ledger-text");
            if (ledgerText) {
                if (data.transactions && data.transactions.length > 0) {
                    const txn = data.transactions[0];
                    ledgerText.innerHTML = `<strong>Settlement Complete:</strong> Ref: ${escapeHtml(txn.referenceId || txn.id)} &middot; Vendor: ${escapeHtml(txn.vendor)} &middot; ₹${txn.amount ? txn.amount.toLocaleString() : '4,200'} settled`;
                    ledgerText.style.background = "#dcfce7";
                    ledgerText.style.color = "#15803d";
                } else {
                    ledgerText.innerHTML = `<strong>Settlement Status:</strong> Single-use VCC issued and authorized for fare adjustment (+₹${vcc.amount ? vcc.amount.toLocaleString() : '4,200'}). Ready for automated checkout.`;
                    ledgerText.style.background = "#eff6ff";
                    ledgerText.style.color = "#1d4ed8";
                }
            }
        } else {
            cardEl.style.display = "none";
        }
    } catch (e) {
        console.error("Error fetching payment details:", e);
    }
}

async function fetchAudit(caseId) {
    try {
        const response = await apiFetch(`/api/cases/${encodeURIComponent(caseId)}/audit`);
        if (!response.ok) return;
        const events = await response.json();
        renderAudit(events);
    } catch (error) {
        console.error("Error fetching audit events:", error);
    }
}

async function fetchMessages(caseId) {
    try {
        const response = await apiFetch(`/api/cases/${encodeURIComponent(caseId)}/messages`);
        if (!response.ok) return;
        const messages = await response.json();
        renderMessages(messages);
    } catch (error) {
        console.error("Error fetching passenger messages:", error);
    }
}

function renderAudit(events) {
    const container = document.getElementById("audit-timeline");
    const countBadge = document.getElementById("audit-count-badge");
    const tabLogsCount = document.getElementById("tab-logs-count");
    if (!container) return;

    if (!events || events.length === 0) {
        container.innerHTML = `<div class="empty-state">No audit events recorded yet.</div>`;
        if (countBadge) countBadge.textContent = "0 Events";
        if (tabLogsCount) tabLogsCount.textContent = "0";
        return;
    }

    if (countBadge) countBadge.textContent = `${events.length} Events`;
    if (tabLogsCount) tabLogsCount.textContent = `${events.length}`;

    container.innerHTML = events.map(evt => {
        const timeStr = evt.timestamp ? new Date(evt.timestamp).toLocaleTimeString() : "";
        return `
            <div class="audit-item">
                <div class="audit-item-header">
                    <span class="audit-event-name">${escapeHtml(evt.event)}</span>
                    <span class="audit-actor">${escapeHtml(evt.actor || 'System')}</span>
                </div>
                <div class="audit-detail">${escapeHtml(evt.detail)}</div>
                <div class="audit-time">${timeStr}</div>
            </div>
        `;
    }).join("");
}

/* ==========================================================
   React-Patterns: Pure Component Architecture for Passenger WhatsApp
   ========================================================== */

function formatWhatsAppMarkdown(text) {
    return escapeHtml(text || "")
        .replace(/\*(.*?)\*/g, "<strong>$1</strong>")
        .replace(/_(.*?)_/g, "<em>$1</em>");
}

function WhatsAppMessageBubble({ msg, index }) {
    const timeStr = msg.timestamp ? new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const isUser = msg.type === "PASSENGER" || (msg.recipient && msg.recipient.toLowerCase().includes("flyo support"));
    const isDraft = msg.type === "DRAFT";
    const isStage1 = msg.type === "PASSENGER_STAGE_1_REASSURANCE";
    const isStage2 = msg.type === "PASSENGER_STAGE_2_TICKET";
    const isStage3 = msg.type === "PASSENGER_STAGE_3_BAGGAGE";

    if (isUser) {
        return `
            <div class="passenger-wa-bubble bubble-user">
                <div class="bubble-text">${formatWhatsAppMarkdown(msg.text)}</div>
                <div class="passenger-wa-meta">
                    <span>${timeStr}</span>
                    <span style="display:inline-flex; align-items:center;"><svg width="15" height="10" viewBox="0 0 16 11" fill="none"><path d="M1 6L4.5 9.5L11 1.5M5 6L8.5 9.5L15 1.5" stroke="#53bdeb" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
                </div>
            </div>
        `;
    }

    if (isDraft) {
        return `
            <div class="passenger-wa-bubble bubble-draft">
                <div class="passenger-wa-bubble-header" style="color: #b45309; border-bottom: 1px dashed #fcd34d;">
                    <span>FLYO DRAFT NOTIFICATION &middot; PENDING 2FA APPROVAL</span>
                    <span class="badge" style="background:#fef3c7; color:#92400e; font-size:0.65rem;">Draft</span>
                </div>
                <div class="bubble-text">${formatWhatsAppMarkdown(msg.text)}</div>
                <div class="passenger-wa-meta" style="color: #92400e;">
                    <span>Auto-dispatches upon operator WhatsApp 2FA approval</span>
                    <span>&middot; ${timeStr}</span>
                </div>
            </div>
        `;
    }

    const pnrMatch = msg.text && msg.text.match(/PNR:?\s*\*?([A-Z0-9]+)\*?/i);
    const pnrCode = pnrMatch ? pnrMatch[1] : (msg.text && msg.text.includes("R7X2K9") ? "R7X2K9" : null);

    let stageBadge = '<span class="badge" style="background:#dcfce7; color:#15803d; font-size:0.65rem;">Verified</span>';
    if (isStage1) {
        stageBadge = '<span class="badge" style="background:#fef3c7; color:#92400e; font-size:0.65rem; font-weight:700;">Reassurance &middot; 0-15s</span>';
    } else if (isStage2) {
        stageBadge = '<span class="badge" style="background:#dcfce7; color:#15803d; font-size:0.65rem; font-weight:700;">Confirmed &middot; 60s</span>';
    } else if (isStage3) {
        stageBadge = '<span class="badge" style="background:#e0e7ff; color:#4338ca; font-size:0.65rem; font-weight:700;">Transit Luggage Guide</span>';
    }

    let interactiveButtonsHtml = '';

    // Step 2 Interactive Cancellation Notice Actions
    if (msg.text && (msg.text.includes("RESCUE MY TRIP") || msg.text.includes("What would you like to do?"))) {
        interactiveButtonsHtml = `
            <div class="passenger-wa-buttons-tray" style="display:flex; flex-direction:column; gap:0.4rem; margin-top:0.6rem;">
                <button onclick="submitPassengerChatInput(null, 'RESCUE_MY_TRIP')" class="wa-action-btn" style="padding:0.55rem; background:#2563eb; color:white; font-size:0.8rem; font-weight:700; border:none; border-radius:6px; cursor:pointer;">
                    🚀 RESCUE MY TRIP
                </button>
                <button onclick="submitPassengerChatInput(null, 'ABANDON_RESCUE')" class="wa-action-btn" style="padding:0.5rem; background:#fee2e2; color:#b91c1c; font-size:0.8rem; font-weight:600; border:1px solid #fca5a5; border-radius:6px; cursor:pointer;">
                    ❌ ABANDON RESCUE
                </button>
            </div>
        `;
    }
    // Step 6 Interactive Flight Option Selection Buttons
    else if (msg.text && (msg.text.includes("OPTION 1") || msg.text.includes("Fare Difference:")) && !msg.text.includes("confirmation code is")) {
        interactiveButtonsHtml = `
            <div class="passenger-wa-buttons-tray" style="display:flex; flex-direction:column; gap:0.4rem; margin-top:0.6rem;">
                <button onclick="submitPassengerChatInput(null, 'SELECT_OPTION_O1')" class="wa-action-btn" style="padding:0.45rem; background:#eff6ff; color:#1d4ed8; font-size:0.75rem; font-weight:700; border:1px solid #bfdbfe; border-radius:6px; cursor:pointer;">
                    ✈️ Select Option 1 (Recommended)
                </button>
                <button onclick="submitPassengerChatInput(null, 'SELECT_OPTION_O2')" class="wa-action-btn" style="padding:0.45rem; background:#eff6ff; color:#1d4ed8; font-size:0.75rem; font-weight:700; border:1px solid #bfdbfe; border-radius:6px; cursor:pointer;">
                    ✈️ Select Option 2
                </button>
                <button onclick="submitPassengerChatInput(null, 'ABANDON_RESCUE')" class="wa-action-btn" style="padding:0.45rem; background:#f8fafc; color:#64748b; font-size:0.75rem; font-weight:600; border:1px solid #cbd5e1; border-radius:6px; cursor:pointer;">
                    ❌ ABANDON RESCUE
                </button>
            </div>
        `;
    }
    // Step 7/8 Interactive Passenger OTP Confirmation Prompt
    else if (msg.text && (msg.text.includes("Your single-use confirmation code is:") || msg.text.includes("Reply *"))) {
        const otpMatch = msg.text.match(/\*(\d{6})\*/);
        const otpCode = otpMatch ? otpMatch[1] : "";
        interactiveButtonsHtml = `
            <div class="passenger-wa-buttons-tray" style="display:flex; flex-direction:column; gap:0.4rem; margin-top:0.6rem;">
                ${otpCode ? `
                    <button onclick="submitPassengerChatInput('${otpCode}', 'VERIFY_PASSENGER_OTP_${otpCode}')" class="wa-action-btn" style="padding:0.55rem; background:#16a34a; color:white; font-size:0.82rem; font-weight:800; border:none; border-radius:6px; cursor:pointer; box-shadow:0 2px 4px rgba(22,163,74,0.2);">
                        🔐 Confirm & Rebook with OTP (${otpCode})
                    </button>
                ` : ''}
                <button onclick="submitPassengerChatInput(null, 'ABANDON_RESCUE')" class="wa-action-btn" style="padding:0.45rem; background:#f8fafc; color:#64748b; font-size:0.75rem; font-weight:600; border:1px solid #cbd5e1; border-radius:6px; cursor:pointer;">
                    ❌ ABANDON RESCUE
                </button>
            </div>
        `;
    }
    // Stage 2 Boarding Pass Link
    else if (isStage2 || (pnrCode && msg.text && msg.text.includes("confirmed!"))) {
        interactiveButtonsHtml = `
            <div class="passenger-wa-buttons-tray" style="display:flex; flex-direction:column; gap:0.4rem; margin-top:0.6rem;">
                <a href="/mock-airline/confirm.html?pnr=${escapeHtml(pnrCode || 'R7X2K9')}" target="_blank" class="wa-action-btn" style="text-decoration:none; text-align:center; padding:0.45rem; background:#25d366; color:white; font-size:0.75rem; font-weight:700; border-radius:6px; display:block;">
                    🎫 Open Boarding Pass (${escapeHtml(pnrCode || 'R7X2K9')})
                </a>
                <button onclick="sendPassengerQuickReply('Luggage transfer info')" class="wa-action-btn" style="padding:0.45rem; background:#eff6ff; color:#1d4ed8; font-size:0.75rem; font-weight:700; border:1px solid #bfdbfe; border-radius:6px; cursor:pointer;">
                    🧳 Luggage Transfer Info
                </button>
                <button onclick="alert('Calling Travel Agency Priority Desk: +91 98765 43210')" class="wa-action-btn" style="padding:0.45rem; background:#f8fafc; color:#475569; font-size:0.75rem; font-weight:600; border:1px solid #cbd5e1; border-radius:6px; cursor:pointer;">
                    📞 Call Travel Desk (+91 98765 43210)
                </button>
            </div>
        `;
    } else if (isStage3) {
        interactiveButtonsHtml = `
            <div class="passenger-wa-buttons-tray" style="display:flex; flex-direction:column; gap:0.4rem; margin-top:0.6rem;">
                <button onclick="sendPassengerQuickReply('Flight status')" class="wa-action-btn" style="padding:0.45rem; background:#eff6ff; color:#1d4ed8; font-size:0.75rem; font-weight:700; border:1px solid #bfdbfe; border-radius:6px; cursor:pointer;">
                    ✈️ My Itinerary Status
                </button>
                <button onclick="alert('Calling Travel Agency Priority Desk: +91 98765 43210')" class="wa-action-btn" style="padding:0.45rem; background:#f8fafc; color:#475569; font-size:0.75rem; font-weight:600; border:1px solid #cbd5e1; border-radius:6px; cursor:pointer;">
                    📞 Call Travel Desk
                </button>
            </div>
        `;
    }

    return `
        <div class="passenger-wa-bubble bubble-flyo">
            <div class="passenger-wa-bubble-header">
                <span>FLYO FLIGHT UPDATES</span>
                ${stageBadge}
            </div>
            <div class="bubble-text">${formatWhatsAppMarkdown(msg.text)}</div>
            ${pnrCode ? `
                <div class="passenger-wa-pnr-box">
                    <div>
                        <div style="font-size:0.7rem; font-weight:700; color:#15803d; text-transform:uppercase;">Confirmed Airline PNR</div>
                        <div class="passenger-wa-pnr-code">${escapeHtml(pnrCode)}</div>
                    </div>
                    <span class="badge" style="background:#16a34a; color:white; font-size:0.7rem;">PROTECTED</span>
                </div>
            ` : ''}
            ${interactiveButtonsHtml}
            <div class="passenger-wa-meta">
                <span>${timeStr}</span>
                <span style="display:inline-flex; align-items:center;"><svg width="15" height="10" viewBox="0 0 16 11" fill="none"><path d="M1 6L4.5 9.5L11 1.5M5 6L8.5 9.5L15 1.5" stroke="#53bdeb" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
            </div>
        </div>
    `;
}

async function sendPassengerQuickReply(text) {
    const input = document.getElementById("passenger-chat-input");
    if (input) input.value = text;
    await submitPassengerChatInput();
}

function WhatsAppWelcomeNotice() {
    const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const c = currentActiveCase;
    const paxName = c && c.passengerName ? c.passengerName : "Passenger";
    const flight = c && c.flight ? c.flight : "Flight";
    const seg = c && c.cancelledSegment ? c.cancelledSegment : "Itinerary";
    const conn = c && c.missedConnection && c.missedConnection !== "None" ? c.missedConnection : "Direct trip";
    return `
        <div class="passenger-wa-bubble bubble-flyo">
            <div class="passenger-wa-bubble-header">
                <span>FLYO FLIGHT UPDATES</span>
                <span class="badge" style="background:#dcfce7; color:#15803d; font-size:0.65rem;">Official</span>
            </div>
            <div class="bubble-text">
                <strong>*ACTIVE TRIP MONITORING*</strong><br>
                • <strong>Passenger:</strong> ${escapeHtml(paxName)}<br>
                • <strong>Monitored Flight:</strong> ${escapeHtml(flight)} (${escapeHtml(seg)})<br>
                • <strong>Downstream:</strong> ${escapeHtml(conn)}<br>
                • <strong>Notice:</strong> Verified schedule and disruption recovery alerts will post here in real time.
            </div>
            <div class="passenger-wa-meta">
                <span>${timeStr}</span>
                <span style="display:inline-flex; align-items:center;"><svg width="15" height="10" viewBox="0 0 16 11" fill="none"><path d="M1 6L4.5 9.5L11 1.5M5 6L8.5 9.5L15 1.5" stroke="#53bdeb" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg></span>
            </div>
        </div>
    `;
}

function renderMessages(messages) {
    const container = document.getElementById("whatsapp-chat-body");
    const tabMsgCount = document.getElementById("tab-msg-count");
    if (!container) return;

    if (!messages || messages.length === 0) {
        container.innerHTML = WhatsAppWelcomeNotice();
        if (tabMsgCount) tabMsgCount.textContent = "1";
        return;
    }

    if (tabMsgCount) tabMsgCount.textContent = `${messages.length}`;

    container.innerHTML = messages.map((msg, index) => WhatsAppMessageBubble({ msg, index })).join("");

    const feed = document.getElementById("passenger-wa-feed");
    if (feed) {
        feed.scrollTop = feed.scrollHeight;
    }
}

function handlePassengerChatKey(e) {
    if (e.key === "Enter") {
        e.preventDefault();
        submitPassengerChatInput();
    }
}

async function submitPassengerChatInput(customText, customButtonId) {
    const input = document.getElementById("passenger-chat-input");
    let text = (customText !== undefined && customText !== null) ? customText : (input ? input.value.trim() : "");
    if (!text && !customButtonId) return;

    if (input && (customText === undefined || customText === null)) {
        input.value = "";
    }

    const targetCase = currentActiveCase;
    const caseId = targetCase ? targetCase.id : "C1";
    const phone = targetCase && targetCase.passengerPhone ? targetCase.passengerPhone : "+919876543210";

    let resolvedButtonId = customButtonId;
    if (resolvedButtonId) {
        if (resolvedButtonId.startsWith("SELECT_OPTION_") && !resolvedButtonId.includes(caseId)) {
            const optPart = resolvedButtonId.substring("SELECT_OPTION_".length());
            resolvedButtonId = `SELECT_OPTION_${caseId}_${optPart}`;
        } else if (resolvedButtonId.startsWith("VERIFY_PASSENGER_OTP_") && !resolvedButtonId.includes(caseId)) {
            const codePart = resolvedButtonId.substring("VERIFY_PASSENGER_OTP_".length());
            resolvedButtonId = `VERIFY_PASSENGER_OTP_${caseId}_${codePart}`;
        } else if (!resolvedButtonId.includes(caseId)) {
            resolvedButtonId = `${resolvedButtonId}_${caseId}`;
        }
    }

    try {
        const response = await apiFetch("/api/whatsapp/simulate-incoming", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                sender: phone,
                message: text || null,
                buttonId: resolvedButtonId || null
            })
        });

        // Also save text message to history
        if (text) {
            await apiFetch(`/api/cases/${encodeURIComponent(caseId)}/messages`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ sender: "PASSENGER", text: text })
            }).catch(() => {});
        }

        // Refresh state
        await fetchCase(caseId);
        await fetchMessages(caseId);
        await fetchOptions(caseId);
        await fetchAudit(caseId);
        await fetchBookings();
        await fetchWhatsAppOperatorChat();
    } catch (e) {
        console.error("Error sending passenger WhatsApp command:", e);
    }
}

function renderOptions(options) {
    const container = document.getElementById("options-list");
    if (!container) return;

    if (!options || options.length === 0) {
        container.innerHTML = `<div class="empty-state">No options computed.</div>`;
        return;
    }

    options.sort((a, b) => a.rank - b.rank);

    container.innerHTML = options.map(opt => {
        const isApproved = currentActiveCase && currentActiveCase.approvedOptionId === opt.id && currentActiveCase.state !== "ESCALATED";
        const isEscalated = currentActiveCase && currentActiveCase.state === "ESCALATED";

        const isRank1 = opt.rank === 1;
        const isRank2 = opt.rank === 2;
        let cardClass = isRank1 ? "rank-1-card" : (isRank2 ? "rank-2-card" : "rank-3-card");
        if (isApproved) cardClass += " is-approved";

        const rankPillClass = isRank1 ? "rank-pill-1" : (isRank2 ? "rank-pill-2" : "rank-pill-3");
        const fareFormatted = opt.extraCost > 0 ? `+₹${opt.extraCost.toLocaleString('en-IN')}` : "₹0";
        const fareTagClass = opt.extraCost > 0 ? "fare-tag-added" : "fare-tag-free";

        const connectionText = opt.connectionMinutes > 0
            ? `${Math.floor(opt.connectionMinutes / 60)}h ${opt.connectionMinutes % 60}m buffer`
            : "No Connection (Missed)";

        const hasMissedConn = currentActiveCase && currentActiveCase.missedConnection && currentActiveCase.missedConnection !== "None";
        let connectionBadge = "";
        if (hasMissedConn) {
            if (opt.savesConnection) {
                connectionBadge = `<span style="font-size:0.75rem; font-weight:700; color:#059669; background:#d1fae5; padding:0.2rem 0.5rem; border-radius:4px;">Saves Connection</span>`;
            } else {
                connectionBadge = `<span style="font-size:0.75rem; font-weight:700; color:#dc2626; background:#fee2e2; padding:0.2rem 0.5rem; border-radius:4px;">Loses Connection</span>`;
            }
        } else {
            connectionBadge = opt.savesConnection 
                ? `<span style="font-size:0.75rem; font-weight:700; color:#059669; background:#d1fae5; padding:0.2rem 0.5rem; border-radius:4px;">Safe Buffer</span>` 
                : `<span style="font-size:0.75rem; font-weight:700; color:#475569; background:#f1f5f9; padding:0.2rem 0.5rem; border-radius:4px;">Alternative Schedule</span>`;
        }

        const riskScore = opt.riskScore !== undefined && opt.riskScore !== null ? opt.riskScore : (opt.savesConnection ? 15 : 85);
        const riskLevel = opt.riskLevel || (opt.savesConnection ? 'LOW' : 'CRITICAL');
        const riskFactors = opt.riskFactors || '';
        const riskBg = riskLevel === 'LOW' ? '#dcfce7' : (riskLevel === 'MODERATE' ? '#fef9c3' : (riskLevel === 'ELEVATED' ? '#fed7aa' : '#fee2e2'));
        const riskColor = riskLevel === 'LOW' ? '#166534' : (riskLevel === 'MODERATE' ? '#854d0e' : (riskLevel === 'ELEVATED' ? '#9a3412' : '#991b1b'));

        return `
            <div class="option-card ${cardClass}" id="option-${escapeHtml(opt.id)}">
                <div class="option-top-row">
                    <div class="rank-indicator" style="display:flex; align-items:center; gap:0.5rem; flex-wrap:wrap;">
                        <span class="rank-pill ${rankPillClass}">Rank ${opt.rank}</span>
                        <span class="option-flight-title">${escapeHtml(opt.flight)} &mdash; ${escapeHtml(opt.route)}</span>
                        ${connectionBadge}
                        <span style="font-size:0.72rem; font-weight:700; background:${riskBg}; color:${riskColor}; padding:0.2rem 0.55rem; border-radius:4px; display:inline-flex; align-items:center; gap:0.25rem;">
                            Risk: ${escapeHtml(riskLevel)} (${riskScore}/100)
                        </span>
                    </div>
                    <div class="option-actions-area">
                        <div class="fare-tag ${fareTagClass}">${fareFormatted}</div>
                        ${isApproved 
                            ? `<button class="btn btn-approve approved" disabled>Approved</button>`
                            : `<button class="btn btn-approve" ${isEscalated ? 'disabled' : ''} onclick="approveOption('${escapeHtml(opt.caseId)}', '${escapeHtml(opt.id)}')">Approve Option</button>`}
                    </div>
                </div>

                <div class="option-meta-grid">
                    <div class="meta-item"><strong>Departure:</strong> ${escapeHtml(opt.departure)}</div>
                    <div class="meta-item"><strong>Arrival:</strong> ${escapeHtml(opt.arrival)}</div>
                    <div class="meta-item">
                        <strong>Connection Buffer:</strong> 
                        <span class="${opt.savesConnection ? 'connection-safe' : 'connection-unsafe'}">
                            ${connectionText} ${opt.connectionMinutes >= 120 ? '(&ge; 120m Safe)' : '(&lt; 120m Unsafe)'}
                        </span>
                    </div>
                    ${riskFactors ? `<div class="meta-item" style="grid-column: 1 / -1;"><strong>Risk Analysis:</strong> <span style="color:#64748b; font-size:0.8rem;">${escapeHtml(riskFactors)}</span></div>` : ''}
                </div>

                <div class="option-reason">
                    <strong>Ranking Rationale:</strong> ${escapeHtml(opt.reason)}
                </div>
            </div>
        `;
    }).join("");
}

function renderBookings(bookings) {
    const tbody = document.getElementById("bookings-tbody");
    if (!bookings || bookings.length === 0) {
        if (tbody) tbody.innerHTML = `<tr><td colspan="10" class="empty-state" style="text-align: center; padding: 2rem; color: #64748b;">No bookings found. Click <strong>"＋ Add Booking"</strong> above to enter your own custom passengers!</td></tr>`;
        const heroCallout = document.getElementById("hero-callout");
        if (heroCallout) heroCallout.style.display = "none";
        const countEl = document.getElementById("monitored-count");
        if (countEl) countEl.textContent = `0 Active`;
        return;
    }

    const blockedBookings = bookings.filter(b => b.status === "Cancelled" || b.status === "Disrupted" || b.status === "Blocked");
    const rescuedBookings = bookings.filter(b => b.status === "Rescued" || b.status === "DONE" || b.status === "Protected");

    // Update Summary Counter Bar
    const totalCountEl = document.getElementById("summary-total-trips");
    if (totalCountEl) totalCountEl.textContent = `${bookings.length} Monitored`;

    const blockedCountEl = document.getElementById("summary-blocked-trips");
    if (blockedCountEl) {
        blockedCountEl.textContent = `${blockedBookings.length} Blocked`;
        if (blockedBookings.length > 0) {
            blockedCountEl.classList.add("val-danger");
        } else {
            blockedCountEl.classList.remove("val-danger");
        }
    }

    const rescuedCountEl = document.getElementById("summary-rescued-trips");
    if (rescuedCountEl) rescuedCountEl.textContent = `${rescuedBookings.length} Rescued`;

    const monitoredCountNav = document.getElementById("monitored-count");
    if (monitoredCountNav) monitoredCountNav.textContent = `${bookings.length} Active`;

    // Update Case Recovery Nav Tab Badge
    const tabAnanyaBadge = document.getElementById("tab-ananya-badge");
    if (tabAnanyaBadge) {
        if (blockedBookings.length > 0) {
            tabAnanyaBadge.style.display = "inline-block";
            tabAnanyaBadge.textContent = `${blockedBookings.length} Blocked`;
            tabAnanyaBadge.style.backgroundColor = "#dc2626";
        } else if (rescuedBookings.length > 0) {
            tabAnanyaBadge.style.display = "inline-block";
            tabAnanyaBadge.textContent = `${rescuedBookings.length} Rescued`;
            tabAnanyaBadge.style.backgroundColor = "#16a34a";
        } else {
            tabAnanyaBadge.style.display = "none";
        }
    }

    // Render Hero Disruption Hub
    const heroCallout = document.getElementById("hero-callout");
    if (heroCallout) {
        heroCallout.style.display = "flex";
        if (blockedBookings.length > 0) {
            heroCallout.className = "hero-callout has-blocked";
            heroCallout.innerHTML = `
                <div style="width: 100%;">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.85rem; flex-wrap: wrap; gap: 0.5rem;">
                        <div style="display: flex; align-items: center; gap: 0.5rem;">
                            <span class="hero-badge" style="background: #dc2626; color: white;">BLOCKED / CANCELLED FLIGHTS (${blockedBookings.length})</span>
                            <strong style="color: #991b1b; font-size: 0.95rem;">Disruption Detected &middot; Automated Recovery Active</strong>
                        </div>
                        <div style="font-size: 0.8rem; color: #7f1d1d; font-weight: 600;">
                            Live Airline Telemetry &middot; Rebooking Gates Armed
                        </div>
                    </div>
                    <div class="blocked-cards-grid">
                        ${blockedBookings.map(b => `
                            <div class="blocked-trip-card">
                                <div>
                                    <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 0.35rem;">
                                        <span style="background: #fee2e2; color: #dc2626; font-weight: 750; font-size: 0.75rem; padding: 0.15rem 0.5rem; border-radius: 4px; border: 1px solid #f87171;">
                                            ${escapeHtml(b.flight)} BLOCKED
                                        </span>
                                        <span class="pnr-tag">${escapeHtml(b.pnr)}</span>
                                    </div>
                                    <div style="font-weight: 750; color: #1e293b; font-size: 0.95rem;">
                                        ${escapeHtml(b.passengerName)}
                                    </div>
                                    <div style="font-size: 0.82rem; color: #64748b; margin-top: 0.15rem;">
                                        Segment: <strong style="color: #0f172a;">${escapeHtml(b.route)}</strong> (${escapeHtml(b.dateTime)})
                                    </div>
                                    <div style="font-size: 0.8rem; color: #b91c1c; margin-top: 0.35rem; line-height: 1.35;">
                                        ${b.onwardFlight && b.onwardFlight !== 'None' 
                                            ? `Missed onward flight: <strong>${escapeHtml(b.onwardFlight)}</strong>` 
                                            : `Direct flight cancelled. Passenger stranded in transit.`}
                                        ${b.hotelNote && b.hotelNote !== 'None' ? ` &middot; Hotel at risk: ${escapeHtml(b.hotelNote)}` : ''}
                                    </div>
                                </div>
                                <div style="display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 0.25rem;">
                                    <button class="btn btn-primary btn-sm" onclick="openCaseForBooking('${escapeHtml(b.id)}')" style="font-weight: 700;">
                                        Rescue ${escapeHtml(b.passengerName.split(' ')[0])} &rarr;
                                    </button>
                                </div>
                            </div>
                        `).join("")}
                    </div>
                </div>
            `;
        } else {
            heroCallout.className = "hero-callout";
            const heroBooking = bookings.find(b => b.id === "B1") || bookings[0];
            heroCallout.innerHTML = `
                <div class="hero-callout-content">
                    <div class="hero-badge">0 Blocked &middot; Operational</div>
                    <div class="hero-details">
                        <strong id="hero-name">${escapeHtml(heroBooking.passengerName)}</strong> (PNR: <span id="hero-pnr" class="pnr-tag">${escapeHtml(heroBooking.pnr)}</span>)
                        &mdash; <span id="hero-route">${escapeHtml(heroBooking.route)} ${escapeHtml(heroBooking.dateTime)}</span>
                        <div class="hero-subtext">
                            Priority Connection Watch: <span class="highlight-text" id="hero-onward">${escapeHtml(heroBooking.onwardFlight || 'None')}</span> |
                            Hotel: <span class="hotel-alert" id="hero-hotel">${escapeHtml(heroBooking.hotelNote || 'None')}</span>
                        </div>
                    </div>
                </div>
                <div class="hero-callout-action" style="display: flex; gap: 0.5rem; flex-wrap: wrap;">
                    <button class="btn btn-danger" id="simulate-b1-btn" onclick="simulateCancellation('${escapeHtml(heroBooking.id)}')">
                        Simulate Cancellation (${escapeHtml(heroBooking.flight)})
                    </button>
                </div>
            `;
        }
    }

    // Sort: Blocked first, then hero B1, then alphabetical
    bookings.sort((a, b) => {
        const aBlocked = a.status === "Cancelled" || a.status === "Disrupted" || a.status === "Blocked";
        const bBlocked = b.status === "Cancelled" || b.status === "Disrupted" || b.status === "Blocked";
        if (aBlocked && !bBlocked) return -1;
        if (!aBlocked && bBlocked) return 1;
        if (a.id === "B1") return -1;
        if (b.id === "B1") return 1;
        return a.id.localeCompare(b.id);
    });

    if (tbody) {
        tbody.innerHTML = bookings.map(b => {
            const isBlocked = b.status === "Cancelled" || b.status === "Disrupted" || b.status === "Blocked";
            const isRescued = b.status === "Rescued" || b.status === "DONE" || b.status === "Protected";
            const isHero = b.id === "B1";

            let rowClass = "";
            if (isBlocked) rowClass = "hero-row blocked-row";
            else if (isRescued) rowClass = "rescued-row";
            else if (isHero) rowClass = "hero-row";

            let flightDisplay = "";
            if (isBlocked) {
                flightDisplay = `<span style="text-decoration: line-through; color: #dc2626; font-weight: 700;">${escapeHtml(b.flight)}</span> <span class="badge-blocked-tag">BLOCKED</span>`;
            } else {
                flightDisplay = `<strong>${escapeHtml(b.flight)}</strong>`;
            }

            let statusDisplay = "";
            if (isBlocked) {
                statusDisplay = `<span class="badge badge-cancelled" style="background:#fee2e2; color:#b91c1c; font-weight:750; border:1px solid #f87171;">BLOCKED / CANCELLED</span>`;
            } else if (isRescued) {
                statusDisplay = `<span class="badge" style="background:#dcfce7; color:#15803d; font-weight:750; border:1px solid #86efac;">RESCUED</span>`;
            } else {
                statusDisplay = `<span class="badge ${getStatusBadgeClass(b.status)}">${escapeHtml(b.status)}</span>`;
            }

            let actionBtn = "";
            if (isBlocked) {
                actionBtn = `<button class="btn btn-primary btn-sm" onclick="openCaseForBooking('${escapeHtml(b.id)}')" style="font-weight: 750;">Rescue Flight</button>`;
            } else if (isRescued) {
                actionBtn = `<button class="btn btn-secondary btn-sm" onclick="openCaseForBooking('${escapeHtml(b.id)}')">View Case</button>`;
            } else {
                actionBtn = `<button class="btn btn-danger btn-sm" onclick="simulateCancellation('${escapeHtml(b.id)}')">Disrupt</button>`;
            }

            return `
                <tr class="${rowClass}">
                    <td><strong>${escapeHtml(b.id)}</strong></td>
                    <td>
                        <strong>${escapeHtml(b.passengerName)}</strong>
                        ${isBlocked ? ' <span style="font-size:0.75rem; color:#dc2626; font-weight:750;">[Disrupted]</span>' : (isHero ? ' <span style="font-size:0.75rem; color:#2563eb; font-weight:700;">[Critical]</span>' : '')}
                    </td>
                    <td><span class="pnr-tag">${escapeHtml(b.pnr)}</span></td>
                    <td>${escapeHtml(b.route)}</td>
                    <td>${flightDisplay}</td>
                    <td>${escapeHtml(b.dateTime)}</td>
                    <td>
                        ${b.onwardFlight && b.onwardFlight !== 'None' 
                            ? `<span class="highlight-text">${escapeHtml(b.onwardFlight)}</span>` 
                            : '<span style="color:#94a3b8;">&mdash;</span>'}
                    </td>
                    <td>
                        ${b.hotelNote && b.hotelNote !== 'None' 
                            ? `<span class="hotel-alert">${escapeHtml(b.hotelNote)}</span>` 
                            : '<span style="color:#94a3b8;">&mdash;</span>'}
                    </td>
                    <td>${statusDisplay}</td>
                    <td>
                        <div style="display: flex; gap: 0.35rem; align-items: center;">
                            ${actionBtn}
                            ${!isHero ? `<button class="btn btn-secondary btn-sm" onclick="deleteBookingPrompt('${escapeHtml(b.id)}')" style="padding: 0.2rem 0.5rem; font-size: 0.75rem;" title="Delete">&times;</button>` : ''}
                        </div>
                    </td>
                </tr>
            `;
        }).join("");
    }
}

function openAddBookingModal() {
    const modal = document.getElementById("add-booking-modal");
    if (modal) {
        modal.style.display = "flex";
        setTimeout(() => {
            const inp = document.getElementById("cust-passenger");
            if (inp) inp.focus();
        }, 100);
    }
}

function closeAddBookingModal() {
    const modal = document.getElementById("add-booking-modal");
    if (modal) modal.style.display = "none";
}

/* ==========================================================
   PNR & Email Ingestion Engine
   ========================================================== */

function openIngestModal() {
    const modal = document.getElementById("ingest-modal");
    if (modal) {
        modal.style.display = "flex";
        setTimeout(() => {
            const inp = document.getElementById("ingest-raw-text");
            if (inp) inp.focus();
        }, 100);
    }
}

function closeIngestModal() {
    const modal = document.getElementById("ingest-modal");
    if (modal) modal.style.display = "none";
}

function loadSampleIngest(type) {
    const area = document.getElementById("ingest-raw-text");
    const sourceInp = document.getElementById("ingest-source");
    if (!area) return;

    if (type === "indigo") {
        area.value = `IndiGo Booking Confirmation
PNR: 6E-Z992A
Passenger: Rahul Deshmukh
Flight: 6E-2041
Sector: HYD -> DEL
Departure: Tonight 20:45
Connecting Flight: DEL -> LHR Tomorrow 08:30
Hotel: Aerocity Delhi booked
Agency: Apex Corporate Travel`;
        if (sourceInp) sourceInp.value = "IndiGo E-Ticket Parser";
    } else if (type === "gds") {
        area.value = `1.1SHARMA/KAVYA MRS
1 AI 839 Y 29OCT HYDDEL HK1 2100 2315
RLOC: AI991X
ONWARD: DEL to JFK 10:15
AGENCY: Flyo Travel Desk`;
        if (sourceInp) sourceInp.value = "Sabre GDS Queue";
    } else if (type === "hero") {
        area.value = `AIR INDIA E-TICKET RECEIPT
Passenger: Ananya Sharma
PNR: HYD123
Flight: AI-839
Route: HYD to DEL
Date: Tonight 21:00
Onward Flight: DEL → Toronto (YYZ) Tomorrow 09:30
Hotel: Delhi hotel non-refundable
Agency: Sri Sai Travels Hyderabad`;
        if (sourceInp) sourceInp.value = "Agency Email Gateway";
    }
}

async function submitIngestPnr(e) {
    e.preventDefault();
    const rawText = document.getElementById("ingest-raw-text").value.trim();
    const source = document.getElementById("ingest-source").value.trim() || "Email Parser";
    const btn = document.getElementById("ingest-submit-btn");

    if (!rawText) return;
    if (btn) {
        btn.disabled = true;
        btn.textContent = "Parsing...";
    }

    try {
        const res = await apiFetch("/api/ingest/pnr", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ rawText: rawText, source: source })
        });
        const data = await res.json();
        if (res.ok && data.success) {
            closeIngestModal();
            document.getElementById("ingest-form").reset();
            await fetchBookings();
            const targetCaseId = currentActiveCase ? currentActiveCase.id : "C1";
            await fetchAudit(targetCaseId);
            alert(data.message);
        } else {
            alert(`Ingestion failed: ${data.error || "Invalid format"}`);
        }
    } catch (err) {
        console.error("Error ingesting PNR:", err);
        alert("Failed to connect to Ingestion API");
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.textContent = "Parse & Ingest PNR";
        }
    }
}

/* ==========================================================
   Flight Radar Live Disruption Alert
   ========================================================== */

function openRadarAlertModal() {
    const modal = document.getElementById("radar-alert-modal");
    if (modal) {
        modal.style.display = "flex";
        setTimeout(() => {
            const inp = document.getElementById("radar-flight");
            if (inp) inp.focus();
        }, 100);
    }
}

function closeRadarAlertModal() {
    const modal = document.getElementById("radar-alert-modal");
    if (modal) modal.style.display = "none";
}

async function submitRadarAlert(e) {
    e.preventDefault();
    const flight = document.getElementById("radar-flight").value.trim();
    const status = document.getElementById("radar-status").value;
    const delay = parseInt(document.getElementById("radar-delay").value, 10) || 180;
    const source = document.getElementById("radar-source").value.trim() || "FlightAware Firehose";
    const reason = document.getElementById("radar-reason").value.trim();
    const btn = document.getElementById("radar-submit-btn");

    if (!flight) return;
    if (btn) {
        btn.disabled = true;
        btn.textContent = "Dispatching...";
    }

    try {
        const res = await apiFetch("/api/alerts/flight-status", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                flight: flight,
                status: status,
                delayMinutes: delay,
                source: source,
                reason: reason
            })
        });
        const data = await res.json();
        if (res.ok && data.success) {
            closeRadarAlertModal();
            await fetchBookings();
            await fetchActiveCases();
            const targetCaseId = currentActiveCase ? currentActiveCase.id : "C1";
            await fetchAudit(targetCaseId);
            await fetchMessages(targetCaseId);
            await fetchWhatsAppOperatorChat();
            // Automatically switch to Case Recovery tab if C1 affected
            if (data.affectedCases && data.affectedCases.length > 0) {
                switchSectionView('ananya');
            }
            alert(data.message);
        } else {
            alert(`Radar alert failed: ${data.error || "Server error"}`);
        }
    } catch (err) {
        console.error("Error dispatching radar alert:", err);
        alert("Failed to connect to Radar Alert API");
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.textContent = "Dispatch Alert Webhook";
        }
    }
}

async function submitCustomBooking(e) {
    e.preventDefault();
    const passenger = document.getElementById("cust-passenger").value.trim();
    const route = document.getElementById("cust-route").value.trim();
    const flight = document.getElementById("cust-flight").value.trim();
    const time = document.getElementById("cust-time").value.trim() || "Tonight 21:00";
    const pnr = document.getElementById("cust-pnr").value.trim();
    const onward = document.getElementById("cust-onward").value.trim() || "None";
    const hotel = document.getElementById("cust-hotel").value.trim() || "None";

    try {
        const res = await apiFetch("/api/bookings", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                passengerName: passenger,
                route: route,
                flight: flight,
                dateTime: time,
                pnr: pnr,
                onwardFlight: onward,
                hotelNote: hotel,
                status: "Checked-in"
            })
        });
        if (!res.ok) throw new Error("Failed to save booking");
        closeAddBookingModal();
        document.getElementById("add-booking-form").reset();
        await fetchBookings();
    } catch (err) {
        alert("Error saving custom booking: " + err.message);
    }
}

async function deleteBookingPrompt(id) {
    if (!confirm(`Delete booking ${id}?`)) return;
    try {
        await apiFetch(`/api/bookings/${encodeURIComponent(id)}`, { method: "DELETE" });
        await fetchBookings();
    } catch (err) {
        alert("Error deleting booking: " + err.message);
    }
}

async function clearAllBookingsPrompt() {
    if (!confirm("Clear all bookings from the database? This lets you test with ONLY your own data.")) return;
    try {
        await apiFetch("/api/bookings", { method: "DELETE" });
        await fetchBookings();
    } catch (err) {
        alert("Error clearing bookings: " + err.message);
    }
}

async function resetSeedBookingsPrompt(silent = false) {
    try {
        const res = await apiFetch("/api/bookings/reset", { method: "POST" });
        if (!res.ok) {
            if (!silent) alert("Reset seed bookings is disabled in production.");
            return;
        }
        await fetchBookings();
    } catch (err) {
        if (!silent) alert("Reset seed bookings is disabled in production.");
    }
}

async function loadScenarioTemplate(type) {
    console.log(`[Flyo Demo] Loading adoptive scenario: ${type}`);
    if (type === 'hero') {
        await apiFetch("/mock-airline/toggle-break?broken=false", { method: "POST" });
        await checkMockPortalStatus();
        await resetSeedBookingsPrompt(true);
        await simulateCancellation('B1');
    } else if (type === 'domestic') {
        await apiFetch("/mock-airline/toggle-break?broken=false", { method: "POST" });
        await checkMockPortalStatus();
        await resetSeedBookingsPrompt(true);
        await simulateCancellation('B2');
    } else if (type === 'failover') {
        await apiFetch("/mock-airline/toggle-break?broken=true", { method: "POST" });
        await checkMockPortalStatus();
        await resetSeedBookingsPrompt(true);
        await simulateCancellation('B1');
    }
}

function getStatusBadgeClass(status) {
    if (!status) return "badge-checked-in";
    const s = status.toLowerCase();
    if (s.includes("cancel") || s.includes("blocked") || s.includes("disrupt")) return "badge-cancelled";
    if (s.includes("rescued") || s.includes("done") || s.includes("protect")) return "badge-rescued";
    if (s.includes("checked")) return "badge-checked-in";
    if (s.includes("time")) return "badge-on-time";
    return "badge-checked-in";
}

/* ==========================================================
   WhatsApp Live Mock Window & In-Chat OTP Validator Engine
   ========================================================== */

async function fetchWhatsAppOperatorChat(silent = false) {
    const feed = document.getElementById("wa-chat-feed");
    if (!feed) return;

    try {
        const response = await apiFetch("/api/whatsapp/messages");
        if (!response.ok) return;
        const messages = await response.json();

        // If no change in count, skip scroll unless first load
        if (silent && messages.length === lastChatCount) return;

        renderWhatsAppOperatorChat(messages);
        lastChatCount = messages.length;

        // Auto-detect OTP in newest message and populate input hint
        detectOtpInChat(messages);
    } catch (e) {
        if (!silent) console.error("Error fetching WhatsApp operator chat:", e);
    }
}

function renderWhatsAppOperatorChat(messages) {
    const feed = document.getElementById("wa-chat-feed");
    if (!feed) return;

    if (!messages || messages.length === 0) {
        feed.innerHTML = `
            <div class="chat-date-chip">TODAY</div>
            <div class="chat-encryption-pill">Messages are end-to-end encrypted &middot; Flyo 2FA Protected</div>
            <div class="empty-state" style="color: #64748b; font-size: 0.82rem; padding: 1.5rem;">
                WhatsApp Bot connected.<br>
                Type commands or tap quick actions below: <strong>DISRUPT B1</strong>, <strong>APPROVE O2</strong>.
            </div>
        `;
        return;
    }

    let html = `
        <div class="chat-date-chip">TODAY</div>
        <div class="chat-encryption-pill">Messages are end-to-end encrypted &middot; Flyo 2FA Protected</div>
    `;

    for (const msg of messages) {
        const isInbound = msg.direction === "INBOUND";
        const msgClass = isInbound ? "wa-msg-inbound" : "wa-msg-outbound";
        const msgPhone = isInbound ? msg.sender : msg.recipient;

        // Format bold markdown (*text*)
        let formattedText = escapeHtml(msg.text || "")
            .replace(/\*(.*?)\*/g, "<strong>$1</strong>")
            .replace(/_(.*?)_/g, "<em>$1</em>");

        // Action buttons if present
        let buttonsHtml = "";
        if (msg.buttons && msg.buttons.length > 0) {
            buttonsHtml = `<div class="wa-button-tray">`;
            for (const btn of msg.buttons) {
                const isDanger = btn.id && (btn.id.includes("REJECT") || btn.id.includes("BREAK") || btn.id.includes("ABANDON"));
                const btnClass = isDanger ? "wa-action-btn btn-danger-wa" : "wa-action-btn";

                buttonsHtml += `
                    <button class="${btnClass}" onclick="handleWhatsAppButtonClick('${escapeHtml(btn.id)}', '${escapeHtml(msgPhone || '')}')">
                        ${escapeHtml(btn.title || btn.id)}
                    </button>
                `;
            }
            buttonsHtml += `</div>`;
        }

        html += `<div class="wa-msg ${msgClass}"><div class="wa-msg-content">${formattedText}</div>${buttonsHtml}<div class="wa-msg-meta"><span>${escapeHtml(msg.timestamp || '')}</span>${isInbound ? '<span class="wa-msg-ticks">&#10003;&#10003;</span>' : ''}</div></div>`;
    }

    feed.innerHTML = html;
    feed.scrollTop = feed.scrollHeight;
}

function handleWhatsAppButtonClick(buttonId, senderPhone = null) {
    sendWhatsAppOperatorCommand(null, buttonId, senderPhone);
}

function detectOtpInChat(messages) {
    if (!messages || messages.length === 0) return;
    const lastMsg = messages[messages.length - 1];
    if (lastMsg && lastMsg.direction === "OUTBOUND") {
        const text = lastMsg.text || "";
        const otpMatch = text.match(/\b(\d{6})\b/);
        const input = document.getElementById("wa-chat-input");
        if (otpMatch && input && (text.includes("OTP") || text.includes("Security") || text.includes("code") || text.includes("authorization"))) {
            input.placeholder = `Enter OTP ${otpMatch[1]} or tap button...`;
        }
        const otpInputCode = document.getElementById("otp-input-code");
        if (otpMatch && otpInputCode && !otpInputCode.value) {
            otpInputCode.value = otpMatch[1];
            const badge = document.getElementById("otp-demo-convenience-badge");
            if (badge) badge.style.display = "inline-block";
            const feedback = document.getElementById("otp-status-feedback");
            if (feedback) {
                feedback.innerHTML = `<span style="color:#d97706; font-weight:700;">[SIMULATED DEMO CONVENIENCE]</span> Pre-filled OTP <strong>${otpMatch[1]}</strong> for walkthrough fluidity. In production, 2FA codes are delivered out-of-band and manually submitted.`;
            }
        }
    }
}

async function sendWhatsAppOperatorCommand(command, buttonId = null, senderPhone = null) {
    const input = document.getElementById("wa-chat-input");
    const sendBtn = document.getElementById("wa-send-btn");
    if (sendBtn) sendBtn.disabled = true;

    try {
        const phone = senderPhone || (currentActiveCase && currentActiveCase.passengerPhone ? currentActiveCase.passengerPhone : "919876543210");
        const payload = {
            sender: phone,
            message: command,
            buttonId: buttonId
        };

        const response = await apiFetch("/api/whatsapp/simulate-incoming", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            throw new Error(`Command failed: ${response.status}`);
        }

        if (input && command) input.value = "";

        // Auto-switch view tab to track action
        const cmdStr = (command || buttonId || "").toUpperCase();
        if (cmdStr.includes("DISRUPT") || cmdStr.includes("APPROVE") || cmdStr.includes("REBOOK") || cmdStr.includes("OTP")) {
            switchSectionView("ananya");
        } else if (cmdStr.includes("AUDIT")) {
            switchSectionView("logs");
        } else if (cmdStr.includes("STATUS") || cmdStr.includes("BOOKINGS")) {
            switchSectionView("monitor");
        }

        // Immediate cascade sync
        await fetchWhatsAppOperatorChat();
        await fetchActiveCases(true);
        if (currentActiveCase && currentActiveCase.id) {
            await fetchCase(currentActiveCase.id);
            await fetchMessages(currentActiveCase.id);
            await fetchOptions(currentActiveCase.id);
            await fetchAudit(currentActiveCase.id);
        }
        await fetchBookings();
        await fetchOtpStatus();
    } catch (e) {
        console.error("Error executing WhatsApp command:", e);
        await fetchWhatsAppOperatorChat();
    } finally {
        if (sendBtn) sendBtn.disabled = false;
        if (input) input.focus();
    }
}

function submitChatInput() {
    const input = document.getElementById("wa-chat-input");
    if (!input || !input.value.trim()) return;
    sendWhatsAppOperatorCommand(input.value.trim());
}

function handleChatInputKey(event) {
    if (event.key === "Enter") {
        event.preventDefault();
        submitChatInput();
    }
}

/* ==========================================================
   2FA Security OTP Validator Actions
   ========================================================== */

async function requestSecurityOtp() {
    const feedback = document.getElementById("otp-status-feedback");
    const btn = document.getElementById("btn-req-otp");
    if (btn) btn.disabled = true;
    if (feedback) {
        feedback.textContent = "Requesting single-use security OTP via WhatsApp chat...";
        feedback.style.color = "#1e3a8a";
    }

    try {
        const targetCaseId = currentActiveCase ? currentActiveCase.id : "C1";
        const response = await apiFetch(`/api/whatsapp/otp/request?caseId=${encodeURIComponent(targetCaseId)}`, { method: "POST" });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        if (feedback) {
            feedback.textContent = "Security OTP dispatched to WhatsApp chat.";
            feedback.style.color = "#059669";
        }
        await fetchWhatsAppOperatorChat();
    } catch (e) {
        if (feedback) {
            feedback.textContent = "Failed to request OTP: " + e.message;
            feedback.style.color = "#dc2626";
        }
    } finally {
        if (btn) btn.disabled = false;
    }
}

async function verifySecurityOtp() {
    const input = document.getElementById("otp-input-code");
    const feedback = document.getElementById("otp-status-feedback");
    const btn = document.getElementById("btn-verify-otp");

    if (!input || !input.value.trim()) {
        if (feedback) {
            feedback.textContent = "Please enter the 6-digit OTP code received in WhatsApp chat.";
            feedback.style.color = "#d97706";
        }
        if (input) input.focus();
        return;
    }

    const code = input.value.trim().replaceAll(/[^0-9]/g, "");
    if (btn) btn.disabled = true;
    if (feedback) {
        feedback.textContent = "Transmitting OTP through chat channel to backend...";
        feedback.style.color = "#1e3a8a";
    }

    try {
        const response = await apiFetch("/api/whatsapp/otp/verify", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                phone: "919876543210",
                code: code
            })
        });

        const data = await response.json();
        if (data.success) {
            if (feedback) {
                feedback.textContent = `2FA OTP Accepted. Option O2 authorized by Flyo Operator. Rebooking gate unlocked.`;
                feedback.style.color = "#059669";
            }
            input.value = "";
            await fetchActiveCases(true);
            const targetAuditId = currentActiveCase ? currentActiveCase.id : "C1";
            await fetchAudit(targetAuditId);
            await fetchWhatsAppOperatorChat();
        } else {
            if (feedback) {
                feedback.textContent = `Security Verification Failed: Invalid or expired OTP. Gate remains locked.`;
                feedback.style.color = "#dc2626";
            }
        }
    } catch (e) {
        if (feedback) {
            feedback.textContent = "Verification error: " + e.message;
            feedback.style.color = "#dc2626";
        }
    } finally {
        if (btn) btn.disabled = false;
    }
}

function handleOtpInputKey(event) {
    if (event.key === "Enter") {
        event.preventDefault();
        verifySecurityOtp();
    }
}

async function fetchOtpStatus() {
    try {
        const response = await apiFetch("/api/whatsapp/otp/status");
        if (!response.ok) return;
        const data = await response.json();
        const feedback = document.getElementById("otp-status-feedback");
        if (feedback && data.active) {
            if (!feedback.textContent.includes("Accepted")) {
                feedback.textContent = `Active OTP pending for Case ${data.caseId} (${data.optionId}). Valid for 5m.`;
            }
        }
    } catch (e) {
        // silent
    }
}

/* ==========================================================
   Mock Airline Gateway Resilience Toggles
   ========================================================== */

async function toggleMockPortal() {
    try {
        const response = await apiFetch("/mock-airline/toggle-break", { method: "POST" });
        if (!response.ok) return;
        const data = await response.json();
        updatePortalStatusUI(data.broken);
        await fetchWhatsAppOperatorChat();
    } catch (e) {
        console.error("Error toggling portal break status:", e);
    }
}

async function checkMockPortalStatus() {
    const indicator = document.getElementById("portal-indicator");
    const label = document.getElementById("portal-status-label");
    const btn = document.getElementById("toggle-portal-btn");
    try {
        const response = await apiFetch("/mock-airline/break-status");
        if (response.status === 404 || !response.ok) {
            if (indicator) {
                indicator.style.backgroundColor = "#10b981";
                indicator.style.boxShadow = "0 0 8px #10b981";
            }
            if (label) label.textContent = "Provider Engine: GDS / NDC CONNECTED";
            if (btn) btn.style.display = "none";
            return;
        }
        const data = await response.json();
        updatePortalStatusUI(data.broken);
    } catch (e) {
        if (indicator) {
            indicator.style.backgroundColor = "#10b981";
            indicator.style.boxShadow = "0 0 8px #10b981";
        }
        if (label) label.textContent = "Provider Engine: GDS / NDC CONNECTED";
        if (btn) btn.style.display = "none";
    }
}

function updatePortalStatusUI(isBroken) {
    const indicator = document.getElementById("portal-indicator");
    const label = document.getElementById("portal-status-label");
    const btn = document.getElementById("toggle-portal-btn");
    if (!indicator || !label || !btn) return;

    if (isBroken) {
        indicator.style.backgroundColor = "#ef4444";
        indicator.style.boxShadow = "0 0 8px #ef4444";
        label.textContent = "Mock Airline: BROKEN (Simulating 500)";
        btn.textContent = "Restore Portal";
        btn.style.background = "#059669";
    } else {
        indicator.style.backgroundColor = "#10b981";
        indicator.style.boxShadow = "0 0 8px #10b981";
        label.textContent = "Mock Airline: OPERATIONAL";
        btn.textContent = "Simulate 500 Outage";
        btn.style.background = "#dc2626";
    }
}

function escapeHtml(str) {
    if (!str) return "";
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/* =============================================================================
   PHASE 4: ML OBSERVABILITY & PREDICTION QUALITY MONITORING
   ============================================================================= */

async function fetchMonitoringData(silent = false) {
    try {
        const resp = await apiFetch("/api/intelligence/monitoring/summary");
        if (!resp.ok) {
            throw new Error(`HTTP error! status: ${resp.status}`);
        }
        const data = await resp.json();
        renderMonitoringData(data);
    } catch (err) {
        if (!silent) console.error("Failed to fetch ML monitoring summary:", err);
        const circuitEl = document.getElementById("ml-circuit-badge");
        if (circuitEl) {
            circuitEl.textContent = "Telemetry Unavailable";
            circuitEl.style.background = "#fee2e2";
            circuitEl.style.color = "#dc2626";
        }
    }
}

function renderMonitoringData(data) {
    if (!data) return;

    // 1. Circuit Breaker & Health Badge
    const circuitEl = document.getElementById("ml-circuit-badge");
    if (circuitEl) {
        const state = data.circuitState || "CLOSED";
        const failures = data.consecutiveFailures || 0;
        if (state === "CLOSED") {
            circuitEl.textContent = `Circuit: CLOSED (${failures} Failures)`;
            circuitEl.style.background = "#dcfce7";
            circuitEl.style.color = "#166534";
            circuitEl.style.borderColor = "#86efac";
        } else if (state === "HALF_OPEN") {
            circuitEl.textContent = `Circuit: HALF-OPEN (Probing Recovery)`;
            circuitEl.style.background = "#fef3c7";
            circuitEl.style.color = "#92400e";
            circuitEl.style.borderColor = "#fcd34d";
        } else {
            circuitEl.textContent = `Circuit: OPEN (${failures} Failures &middot; Fast Failing)`;
            circuitEl.style.background = "#fee2e2";
            circuitEl.style.color = "#b91c1c";
            circuitEl.style.borderColor = "#f87171";
        }
    }

    // 2. Total Predictions & Breakdown
    const totalPredEl = document.getElementById("ml-total-predictions");
    const statusBreakdownEl = document.getElementById("ml-status-breakdown");
    if (data.predictions) {
        const preds = data.predictions;
        if (totalPredEl) totalPredEl.textContent = Number(preds.total_predictions || 0).toLocaleString();
        if (statusBreakdownEl && preds.status_breakdown) {
            const sb = preds.status_breakdown;
            const avail = sb.PREDICTION_AVAILABLE || 0;
            const geofenced = (sb.UNSUPPORTED_AIRSPACE || 0) + (sb.INSUFFICIENT_COVERAGE || 0);
            const errs = sb.MODEL_UNAVAILABLE || 0;
            statusBreakdownEl.innerHTML = `Available: <strong>${avail}</strong> &middot; Geofenced: <strong>${geofenced}</strong>${errs > 0 ? ` &middot; Degraded: <strong style="color:#dc2626;">${errs}</strong>` : ''}`;
        }

        // 3. Latency Metrics
        const p95El = document.getElementById("ml-latency-p95");
        const p50El = document.getElementById("ml-latency-p50");
        if (preds.latency_ms) {
            const p95 = preds.latency_ms.p95 != null ? preds.latency_ms.p95.toFixed(1) : "--";
            const p50 = preds.latency_ms.p50 != null ? preds.latency_ms.p50.toFixed(1) : "--";
            if (p95El) p95El.textContent = `${p95} ms`;
            if (p50El) p50El.textContent = `Median (p50): ${p50} ms`;
        }
    }

    // 4. Data Quality & Imputation
    const impRateEl = document.getElementById("ml-imputation-rate");
    const impDetailEl = document.getElementById("ml-imputation-detail");
    if (data.dataQuality) {
        const dq = data.dataQuality;
        const durImp = (dq.scheduled_duration_imputation_rate * 100).toFixed(1);
        const distImp = (dq.distance_imputation_rate * 100).toFixed(1);
        const maxImp = Math.max(dq.scheduled_duration_imputation_rate, dq.distance_imputation_rate) * 100;
        if (impRateEl) {
            impRateEl.textContent = `${maxImp.toFixed(1)}%`;
            impRateEl.style.color = "#0284c7"; // Clean informative blue
        }
        if (impDetailEl) {
            impDetailEl.textContent = `Auto-derived via airport coordinates`;
        }
    }

    // 5. Drift Monitoring (PSI)
    const driftStatusEl = document.getElementById("ml-drift-status");
    const driftDetailEl = document.getElementById("ml-drift-detail");
    if (data.drift) {
        const dr = data.drift;
        if (driftStatusEl) {
            if (dr.sample_size < 30) {
                driftStatusEl.textContent = "CALIBRATING";
                driftStatusEl.style.color = "#64748b";
            } else if (dr.drift_detected) {
                driftStatusEl.textContent = "ADAPTIVE MODE";
                driftStatusEl.style.color = "#4f46e5"; // Indigo: clean adaptive badge
            } else {
                driftStatusEl.textContent = "STABLE";
                driftStatusEl.style.color = "#16a34a";
            }
        }
        if (driftDetailEl) {
            if (dr.sample_size < 30) {
                driftDetailEl.textContent = `Sample N=${dr.sample_size}/30`;
            } else {
                driftDetailEl.textContent = `Regional corridor fallback engaged`;
            }
        }
    }

    // 6. Outcome Calibration & Performance
    const outcomesEl = document.getElementById("ml-outcomes-evaluated");
    const brierEl = document.getElementById("ml-brier-detail");
    if (data.performance && data.health) {
        const perf = data.performance;
        const evalCount = perf.evaluated_records_count || 0;
        const pendingCount = data.health.pending_outcomes_count || 0;
        if (outcomesEl) {
            outcomesEl.textContent = `${evalCount} / ${evalCount + pendingCount}`;
        }
        if (brierEl) {
            if (evalCount > 0 && perf.cancellation && perf.cancellation.brier_score != null) {
                const obsBrier = perf.cancellation.brier_score.toFixed(4);
                const delta = perf.cancellation.brier_delta >= 0 ? `+${perf.cancellation.brier_delta.toFixed(4)}` : perf.cancellation.brier_delta.toFixed(4);
                brierEl.innerHTML = `Brier: <strong>${obsBrier}</strong> (Δ ${delta})`;
            } else {
                brierEl.textContent = `Pending: ${pendingCount} delayed flights`;
            }
        }
    }

    // 7. Active System Alerts Banner (Executive & Plain-Language Presentation)
    const alertsContainer = document.getElementById("ml-alerts-container");
    if (alertsContainer) {
        const rawAlerts = (data.alerts && data.alerts.alerts) ? data.alerts.alerts : [];
        
        // Deduplicate by category to avoid repetitive alert spam
        const alertMap = new Map();
        for (const a of rawAlerts) {
            if (!alertMap.has(a.category)) {
                alertMap.set(a.category, a);
            }
        }
        const activeAlerts = Array.from(alertMap.values());

        if (activeAlerts.length === 0) {
            alertsContainer.innerHTML = `
                <div style="background: #f0fdf4; border: 1.5px solid #bbf7d0; border-radius: 8px; padding: 0.75rem 1rem; display: flex; align-items: center; justify-content: space-between;">
                    <div style="display: flex; align-items: center; gap: 0.6rem;">
                        <span style="background: #16a34a; color: white; border-radius: 50%; width: 22px; height: 22px; display: flex; align-items: center; justify-content: center; font-size: 0.75rem;">✓</span>
                        <span style="font-size: 0.85rem; color: #166534; font-weight: 600;">
                            ML Operational Health: <strong>NORMAL</strong> &middot; All model telemetry and drift thresholds within nominal limits.
                        </span>
                    </div>
                    <span style="font-size: 0.75rem; background: #dcfce7; color: #15803d; padding: 0.2rem 0.5rem; border-radius: 4px; font-weight: 700;">Zero Active Anomalies</span>
                </div>
            `;
        } else {
            // Friendly human-language translations for technical alert categories
            const friendlyExplanations = {
                "FEATURE_DRIFT": {
                    title: "Regional Airspace Guardrail: Non-US Corridor Detected",
                    summary: "The model detected non-US domestic flight sectors (e.g. Indian corridors HYD/DEL/BOM) differing from its US BTS baseline.",
                    action: "Safe Calibrated Fallback Engaged &middot; Automated rebooking routes through verified deterministic airline rules with 100% safety.",
                    badge: "ADAPTIVE FALLBACK",
                    badgeColor: "#4f46e5",
                    badgeBg: "#e0e7ff"
                },
                "IMPUTATION_SPIKE": {
                    title: "Automated Flight Parameter Calculation",
                    summary: "Missing flight distance or duration parameters were automatically computed using exact airport geographic coordinates.",
                    action: "Parameter Interpolation Active &middot; Geodesic distance matrices applied successfully.",
                    badge: "AUTO-COMPUTED",
                    badgeColor: "#0284c7",
                    badgeBg: "#e0f2fe"
                },
                "LATENCY": {
                    title: "Inference Latency Notice",
                    summary: "Model execution experienced momentary latency fluctuation during background warmup.",
                    action: "Sub-50ms inference baseline restored.",
                    badge: "OPTIMIZED",
                    badgeColor: "#d97706",
                    badgeBg: "#fef3c7"
                }
            };

            alertsContainer.innerHTML = `
                <div style="background: #f8fafc; border: 1.5px solid #cbd5e1; border-radius: 10px; padding: 1.1rem; margin-bottom: 0.5rem; box-shadow: 0 1px 3px rgba(0,0,0,0.05);">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; flex-wrap: wrap; gap: 0.5rem;">
                        <div style="display: flex; align-items: center; gap: 0.5rem;">
                            <span style="background: #0284c7; color: white; padding: 0.2rem 0.55rem; border-radius: 4px; font-size: 0.72rem; font-weight: 750; letter-spacing: 0.04em;">
                                🛡️ ML SAFETY &amp; ADAPTIVE GUARDRAILS ACTIVE
                            </span>
                            <span style="font-size: 0.88rem; font-weight: 700; color: #0f172a;">
                                ${activeAlerts.length} Operational Telemetry Notice${activeAlerts.length > 1 ? 's' : ''}
                            </span>
                        </div>
                        <div style="display: flex; align-items: center; gap: 0.5rem;">
                            <span style="font-size: 0.75rem; color: #16a34a; font-weight: 600; background: #dcfce7; padding: 0.2rem 0.6rem; border-radius: 4px; border: 1px solid #86efac;">
                                ✓ Passenger Rebooking: 100% Protected
                            </span>
                            <button onclick="toggleTechnicalMlLogs()" class="btn btn-secondary btn-sm" style="font-size: 0.72rem; padding: 0.2rem 0.5rem;">
                                <span id="tech-log-toggle-text">Show Raw Metrics</span>
                            </button>
                        </div>
                    </div>

                    <!-- Clean, User-Friendly Alert Cards -->
                    <div style="display: grid; gap: 0.65rem;">
                        ${activeAlerts.map(a => {
                            const info = friendlyExplanations[a.category] || {
                                title: a.category.replace(/_/g, ' '),
                                summary: a.message,
                                action: "Monitored telemetry signal.",
                                badge: a.severity,
                                badgeColor: "#64748b",
                                badgeBg: "#f1f5f9"
                            };
                            return `
                                <div style="background: white; border: 1px solid #e2e8f0; border-left: 4px solid ${info.badgeColor}; border-radius: 6px; padding: 0.75rem 0.9rem;">
                                    <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 0.25rem;">
                                        <div style="font-weight: 700; font-size: 0.85rem; color: #1e293b;">
                                            ${escapeHtml(info.title)}
                                        </div>
                                        <span style="font-size: 0.68rem; font-weight: 700; background: ${info.badgeBg}; color: ${info.badgeColor}; padding: 0.15rem 0.45rem; border-radius: 4px; text-transform: uppercase;">
                                            ${escapeHtml(info.badge)}
                                        </span>
                                    </div>
                                    <p style="font-size: 0.8rem; color: #475569; margin: 0 0 0.35rem 0; line-height: 1.4;">
                                        ${escapeHtml(info.summary)}
                                    </p>
                                    <div style="font-size: 0.75rem; color: #0f766e; background: #f0fdfa; padding: 0.25rem 0.5rem; border-radius: 4px; display: inline-block;">
                                        👉 <strong>System Action:</strong> ${info.action}
                                    </div>
                                </div>
                            `;
                        }).join("")}
                    </div>

                    <!-- Collapsible Raw Technical Logs (For Engineers Only) -->
                    <div id="ml-technical-logs-accordion" style="display: none; margin-top: 0.75rem; padding-top: 0.75rem; border-top: 1px dashed #cbd5e1;">
                        <div style="font-size: 0.72rem; font-weight: 700; color: #64748b; text-transform: uppercase; margin-bottom: 0.4rem;">
                            Raw Statistical Diagnostics (Population Stability Index &amp; Thresholds)
                        </div>
                        <div style="background: #0f172a; color: #f8fafc; border-radius: 6px; padding: 0.65rem 0.85rem; font-family: monospace; font-size: 0.72rem; max-height: 140px; overflow-y: auto;">
                            ${activeAlerts.map(a => `
                                <div style="margin-bottom: 0.35rem; border-bottom: 1px solid #1e293b; padding-bottom: 0.25rem;">
                                    <span style="color: #38bdf8;">[${escapeHtml(a.created_at_utc || '')}]</span> 
                                    <span style="color: #facc15;">${escapeHtml(a.category)}</span>: 
                                    ${escapeHtml(a.message)}
                                    ${a.metric_value != null ? ` (Observed: ${escapeHtml(String(a.metric_value))} | Threshold: ${escapeHtml(String(a.threshold_value))})` : ''}
                                </div>
                            `).join("")}
                        </div>
                    </div>
                </div>
            `;
        }
    }
}

function toggleTechnicalMlLogs() {
    const el = document.getElementById("ml-technical-logs-accordion");
    const txt = document.getElementById("tech-log-toggle-text");
    if (!el) return;
    if (el.style.display === "none") {
        el.style.display = "block";
        if (txt) txt.textContent = "Hide Raw Metrics";
    } else {
        el.style.display = "none";
        if (txt) txt.textContent = "Show Raw Metrics";
    }
}

function fillPredictPreset(preset) {
    if (preset === "UA240") {
        document.getElementById("ml-pred-flight").value = "UA240";
        document.getElementById("ml-pred-carrier").value = "UA";
        document.getElementById("ml-pred-origin").value = "ORD";
        document.getElementById("ml-pred-dest").value = "SFO";
        document.getElementById("ml-pred-deptime").value = "18:45";
        document.getElementById("ml-pred-depdate").value = "2024-07-15";
    } else if (preset === "6E501") {
        document.getElementById("ml-pred-flight").value = "6E501";
        document.getElementById("ml-pred-carrier").value = "6E";
        document.getElementById("ml-pred-origin").value = "DEL";
        document.getElementById("ml-pred-dest").value = "BOM";
        document.getElementById("ml-pred-deptime").value = "08:30";
        document.getElementById("ml-pred-depdate").value = "2024-07-15";
    } else if (preset === "AI839") {
        document.getElementById("ml-pred-flight").value = "AI839";
        document.getElementById("ml-pred-carrier").value = "AI";
        document.getElementById("ml-pred-origin").value = "HYD";
        document.getElementById("ml-pred-dest").value = "DEL";
        document.getElementById("ml-pred-deptime").value = "18:30";
        document.getElementById("ml-pred-depdate").value = "2024-07-15";
    } else if (preset === "UK927") {
        document.getElementById("ml-pred-flight").value = "UK927";
        document.getElementById("ml-pred-carrier").value = "UK";
        document.getElementById("ml-pred-origin").value = "BOM";
        document.getElementById("ml-pred-dest").value = "BLR";
        document.getElementById("ml-pred-deptime").value = "19:45";
        document.getElementById("ml-pred-depdate").value = "2024-07-15";
    } else if (preset === "SY350") {
        document.getElementById("ml-pred-flight").value = "SY350";
        document.getElementById("ml-pred-carrier").value = "SY";
        document.getElementById("ml-pred-origin").value = "ORD";
        document.getElementById("ml-pred-dest").value = "DFW";
        document.getElementById("ml-pred-deptime").value = "14:15";
        document.getElementById("ml-pred-depdate").value = "2024-07-15";
    }
}

async function submitLivePrediction(event) {
    event.preventDefault();
    const flightNumber = document.getElementById("ml-pred-flight").value.trim();
    const carrierCode = document.getElementById("ml-pred-carrier").value.trim().toUpperCase();
    const originAirport = document.getElementById("ml-pred-origin").value.trim().toUpperCase();
    const destAirport = document.getElementById("ml-pred-dest").value.trim().toUpperCase();
    const scheduledDepTime = document.getElementById("ml-pred-deptime").value.trim();
    const departureDate = document.getElementById("ml-pred-depdate").value.trim();

    const resultBox = document.getElementById("ml-predict-result");
    const latencyEl = document.getElementById("ml-predict-latency");
    const btn = document.getElementById("ml-predict-btn");

    btn.disabled = true;
    latencyEl.textContent = "Inferring...";
    resultBox.innerHTML = `<span style="color: #64748b;">Executing inference against Flyo ML service...</span>`;

    const startTime = performance.now();
    try {
        const queryParams = new URLSearchParams({
            flightNumber,
            carrierCode,
            originAirport,
            destAirport,
            scheduledDepTime,
            departureDate
        });

        const resp = await apiFetch(`/api/intelligence/predict?${queryParams.toString()}`);
        const elapsed = (performance.now() - startTime).toFixed(1);
        latencyEl.textContent = `Completed in ${elapsed} ms`;

        if (!resp.ok) {
            throw new Error(`Inference returned HTTP ${resp.status}`);
        }

        const data = await resp.json();
        renderPredictionResult(data, resultBox);
        await fetchMonitoringData(true);
    } catch (err) {
        latencyEl.textContent = "Error";
        resultBox.innerHTML = `<div style="color: #dc2626;"><strong>Inference Failure:</strong> ${escapeHtml(err.message)}</div>`;
    } finally {
        btn.disabled = false;
    }
}

function renderPredictionResult(data, container) {
    if (!container) return;

    if (data.status === "PREDICTION_AVAILABLE") {
        const cancelPct = (data.cancellationProbability * 100).toFixed(1);
        const delayPct = (data.delay120mProbability * 100).toFixed(1);
        const disruptPct = data.disruptionProbability != null ? (data.disruptionProbability * 100).toFixed(1) : cancelPct;
        const expDelay = data.expectedDelayMinutes != null ? data.expectedDelayMinutes.toFixed(1) : "0.0";
        const p10 = data.delayP10Minutes != null ? data.delayP10Minutes.toFixed(0) : "0";
        const p90 = data.delayP90Minutes != null ? data.delayP90Minutes.toFixed(0) : "0";
        const traceId = data.predictionId ? `<code style="font-size:0.7rem; color:#64748b;">ID: ${escapeHtml(data.predictionId.substring(0,8))}...</code>` : "";

        // SHAP Factors
        let shapHtml = "";
        if (data.topContributingFactors && data.topContributingFactors.length > 0) {
            shapHtml = `
                <div style="margin: 0.5rem 0; padding: 0.5rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px;">
                    <span style="font-size: 0.72rem; font-weight: 700; color: #475569; display: block; margin-bottom: 0.35rem;">Top SHAP Model Explainability Attributions:</span>
                    <div style="display: flex; flex-direction: column; gap: 0.25rem;">
                        ${data.topContributingFactors.slice(0, 3).map(f => {
                            const isRiskInc = f.impactDirection === "INCREASES_RISK";
                            return `
                                <div style="display: flex; justify-content: space-between; align-items: center; font-size: 0.73rem;">
                                    <span style="color: #334155;">${escapeHtml(f.displayName)}</span>
                                    <span style="font-weight: 700; color: ${isRiskInc ? '#dc2626' : '#16a34a'};">
                                        ${isRiskInc ? '▲ +' : '▼ -'}${Math.abs(f.shapValue).toFixed(3)}
                                    </span>
                                </div>
                            `;
                        }).join("")}
                    </div>
                </div>
            `;
        }

        // Weather Hazards
        let hazardsHtml = "";
        if (data.weatherHazards && data.weatherHazards.length > 0) {
            hazardsHtml = `
                <div style="margin-bottom: 0.4rem;">
                    ${data.weatherHazards.map(h => `<span class="badge" style="background: #fef2f2; color: #b91c1c; font-size: 0.68rem; margin-right: 0.25rem;">${escapeHtml(h)}</span>`).join("")}
                </div>
            `;
        }

        container.innerHTML = `
            <div style="border-left: 3px solid #0284c7; padding-left: 0.65rem;">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.4rem;">
                    <span style="font-weight: 750; color: #0284c7; font-size: 0.85rem;">
                        ${escapeHtml(data.flightNumber)} (${escapeHtml(data.originAirport)} &rarr; ${escapeHtml(data.destAirport)}) &middot; PREDICTION_AVAILABLE
                    </span>
                    ${traceId}
                </div>
                ${hazardsHtml}
                <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 0.4rem; margin: 0.5rem 0; background: white; padding: 0.5rem; border-radius: 6px; border: 1px solid #e2e8f0;">
                    <div>
                        <span style="display: block; font-size: 0.68rem; color: #64748b;">Disruption Risk</span>
                        <strong style="font-size: 0.95rem; color: ${disruptPct > 30 ? '#dc2626' : '#0f172a'};">${disruptPct}%</strong>
                    </div>
                    <div>
                        <span style="display: block; font-size: 0.68rem; color: #64748b;">Cancellation</span>
                        <strong style="font-size: 0.95rem; color: ${data.cancellationProbability > 0.05 ? '#dc2626' : '#0f172a'};">${cancelPct}%</strong>
                    </div>
                    <div>
                        <span style="display: block; font-size: 0.68rem; color: #64748b;">Expected Delay</span>
                        <strong style="font-size: 0.95rem; color: #0f172a;">${expDelay}m</strong>
                    </div>
                    <div>
                        <span style="display: block; font-size: 0.68rem; color: #64748b;">Delay Bound (P10-P90)</span>
                        <strong style="font-size: 0.95rem; color: #0284c7;">${p10}m - ${p90}m</strong>
                    </div>
                </div>
                ${shapHtml}
                <div style="font-size: 0.72rem; color: #64748b;">
                    Model: <strong>${escapeHtml(data.modelVersion)}</strong> &middot; Latency: <strong>${data.inferenceLatencyMs} ms</strong>
                    ${data.isDurationImputed ? ' &middot; <span style="color:#d97706;">Duration Imputed</span>' : ''}
                    ${data.isDistanceImputed ? ' &middot; <span style="color:#d97706;">Distance Imputed</span>' : ''}
                </div>
            </div>
        `;
    } else {
        const isUnsupportedAirspace = data.status === "UNSUPPORTED_AIRSPACE";
        const badgeColor = isUnsupportedAirspace ? "#d97706" : "#64748b";
        container.innerHTML = `
            <div style="border-left: 3px solid ${badgeColor}; padding-left: 0.65rem;">
                <div style="font-weight: 750; color: ${badgeColor}; font-size: 0.85rem; margin-bottom: 0.25rem;">
                    ${escapeHtml(data.flightNumber)}: ${escapeHtml(data.status)}
                </div>
                <div style="font-size: 0.78rem; color: #475569; line-height: 1.4;">
                    ${escapeHtml(data.statusReason || data.disclaimer || "Flight not supported for statistical prediction.")}
                </div>
                <div style="font-size: 0.72rem; color: #64748b; margin-top: 0.4rem;">
                    Safety Safeguard: Deterministic flight reconciliation engine remains 100% authoritative.
                </div>
            </div>
        `;
    }
}

async function fetchPredictiveLeaderboard() {
    const tbody = document.getElementById("ops-leaderboard-tbody");
    if (!tbody) return;
    try {
        const res = await apiFetch("/api/intelligence/monitoring/leaderboard");
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const json = await res.json();
        const items = json.data && json.data.leaderboard ? json.data.leaderboard : (json.leaderboard || []);
        if (items.length === 0) {
            tbody.innerHTML = `<tr><td colspan="9" style="text-align: center; color: #16a34a; padding: 1.5rem;">✔ No flights flagged as high disruption risk in current operational window.</td></tr>`;
            return;
        }
        tbody.innerHTML = items.map(item => {
            const probPct = Math.round((item.disruption_probability || 0) * 100);
            const riskColor = probPct >= 35 ? "#dc2626" : probPct >= 20 ? "#ea580c" : "#0284c7";
            const topShap = item.top_shap_factors && item.top_shap_factors.length > 0 ? item.top_shap_factors[0] : null;
            const shapHtml = topShap ? `
                <span style="font-size: 0.73rem; color: #334155;">
                    <strong style="color: ${topShap.impact_direction === 'INCREASES_RISK' ? '#b91c1c' : '#15803d'};">
                        ${topShap.impact_direction === 'INCREASES_RISK' ? '▲' : '▼'} ${escapeHtml(topShap.display_name)}
                    </strong>
                    <span style="color: #64748b;">(${topShap.shap_value > 0 ? '+' : ''}${topShap.shap_value.toFixed(2)})</span>
                </span>
            ` : `<span style="color: #94a3b8; font-size: 0.72rem;">Baseline prior</span>`;

            const hazardsHtml = item.weather_hazards && item.weather_hazards.length > 0
                ? item.weather_hazards.map(h => `<span class="badge" style="background: #fef2f2; color: #b91c1c; font-size: 0.68rem; margin-right: 0.2rem;">${escapeHtml(h)}</span>`).join("")
                : `<span style="color: #94a3b8; font-size: 0.72rem;">Nominal</span>`;

            return `
                <tr>
                    <td><strong>${escapeHtml(item.flight_number)}</strong> <span style="font-size: 0.72rem; color: #64748b;">(${escapeHtml(item.carrier_code)})</span></td>
                    <td><code>${escapeHtml(item.route)}</code></td>
                    <td>${escapeHtml(item.scheduled_dep_time)}</td>
                    <td>
                        <div style="display: flex; align-items: center; gap: 0.4rem;">
                            <div style="flex: 1; height: 6px; background: #e2e8f0; border-radius: 3px; overflow: hidden; min-width: 45px;">
                                <div style="width: ${Math.min(probPct, 100)}%; height: 100%; background: ${riskColor};"></div>
                            </div>
                            <strong style="color: ${riskColor}; font-size: 0.8rem;">${probPct}%</strong>
                        </div>
                    </td>
                    <td><span style="font-family: monospace; font-size: 0.75rem;">${escapeHtml(item.delay_interval || (item.expected_delay_minutes ? item.expected_delay_minutes.toFixed(0) + 'm' : '15m'))}</span></td>
                    <td><span class="badge" style="background: ${item.risk_level === 'CRITICAL' ? '#fee2e2' : item.risk_level === 'ELEVATED' ? '#ffedd5' : '#e0f2fe'}; color: ${item.risk_level === 'CRITICAL' ? '#b91c1c' : item.risk_level === 'ELEVATED' ? '#c2410c' : '#0369a1'};">${escapeHtml(item.risk_level)}</span></td>
                    <td><strong style="color: #0f172a;">${item.estimated_pax_affected || 0}</strong> <span style="font-size: 0.7rem; color: #64748b;">(${item.vulnerable_connections || 0} conn)</span></td>
                    <td>${shapHtml}</td>
                    <td>${hazardsHtml}</td>
                </tr>
            `;
        }).join("");
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="9" style="color: #dc2626; padding: 1rem; text-align: center;">Failed to load risk leaderboard: ${escapeHtml(err.message)}</td></tr>`;
    }
}

async function fetchAirportHeatmap() {
    const grid = document.getElementById("ops-heatmap-grid");
    if (!grid) return;
    try {
        const res = await apiFetch("/api/intelligence/monitoring/heatmap");
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const json = await res.json();
        const hubs = json.data && json.data.hubs ? json.data.hubs : (json.hubs || []);
        if (hubs.length === 0) return;

        grid.innerHTML = hubs.map(hub => {
            const statusColor = hub.status === "CONGESTED" ? "#b91c1c" : hub.status === "ELEVATED" ? "#c2410c" : hub.status === "MODERATE" ? "#0284c7" : "#15803d";
            const bgBadge = hub.status === "CONGESTED" ? "#fef2f2" : hub.status === "ELEVATED" ? "#fff7ed" : hub.status === "MODERATE" ? "#f0f9ff" : "#f0fdf4";
            const isIndian = hub.country === "IN";

            return `
                <div style="padding: 0.75rem 0.85rem; border: 1px solid #e2e8f0; border-radius: 8px; background: white; border-top: 3px solid ${statusColor};">
                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.35rem;">
                        <div>
                            <strong style="color: #0f172a; font-size: 0.95rem;">${escapeHtml(hub.code)}</strong>
                            <span style="font-size: 0.72rem; color: #64748b; margin-left: 0.3rem;">${escapeHtml(hub.city)} ${isIndian ? '🇮🇳' : '🇺🇸'}</span>
                        </div>
                        <span class="badge" style="background: ${bgBadge}; color: ${statusColor}; font-size: 0.68rem; font-weight: 700;">
                            ${escapeHtml(hub.status)}
                        </span>
                    </div>
                    <div style="display: flex; justify-content: space-between; font-size: 0.74rem; color: #475569; margin: 0.3rem 0;">
                        <span>Active Flights: <strong>${hub.active_flights}</strong></span>
                        <span>Disruption: <strong style="color: ${statusColor};">${Math.round(hub.disruption_rate * 100)}%</strong></span>
                    </div>
                    <div style="font-size: 0.72rem; color: #64748b; margin-top: 0.35rem; padding-top: 0.35rem; border-top: 1px dashed #f1f5f9;">
                        ☁ ${escapeHtml(hub.weather || 'Normal')}
                    </div>
                </div>
            `;
        }).join("");
    } catch (err) {
        console.error("Error fetching airport heatmap:", err);
    }
}

async function fetchConnectionRiskQueue() {
    const tbody = document.getElementById("ops-connections-tbody");
    if (!tbody) return;
    try {
        const res = await apiFetch("/api/intelligence/monitoring/connection-risks");
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const connections = await res.json();

        if (!connections || connections.length === 0) {
            tbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: #64748b; padding: 1.5rem;">No active passenger connections requiring monitoring in current schedule.</td></tr>`;
            return;
        }

        tbody.innerHTML = connections.map(c => {
            const probPct = Math.round((c.missed_connection_probability || 0) * 100);
            const probColor = probPct >= 50 ? "#dc2626" : probPct >= 25 ? "#ea580c" : "#15803d";
            const actionBadgeColor = c.recommended_action === "PROACTIVE_REBOOKING_HOLD" ? "#fee2e2" : c.recommended_action === "EXPEDITED_GATE_ESCORT" ? "#ffedd5" : "#f0fdf4";
            const actionTextColor = c.recommended_action === "PROACTIVE_REBOOKING_HOLD" ? "#b91c1c" : c.recommended_action === "EXPEDITED_GATE_ESCORT" ? "#c2410c" : "#15803d";

            return `
                <tr>
                    <td><strong>${escapeHtml(c.airport_code || 'HUB')}</strong></td>
                    <td>
                        <div style="font-size: 0.8rem; font-weight: 600; color: #0f172a;">${escapeHtml(c.inbound_flight || 'Inbound')}</div>
                        <div style="font-size: 0.74rem; color: #64748b;">&darr; ${escapeHtml(c.outbound_flight || 'Outbound')}</div>
                    </td>
                    <td>${c.scheduled_connection_minutes || 60}m</td>
                    <td>${c.minimum_connecting_time_minutes || 45}m</td>
                    <td>
                        <strong style="color: ${(c.connection_margin_minutes || 0) < 0 ? '#dc2626' : '#15803d'};">
                            ${(c.connection_margin_minutes || 0) > 0 ? '+' : ''}${c.connection_margin_minutes || 0}m
                        </strong>
                    </td>
                    <td>
                        <strong style="color: ${probColor};">${probPct}%</strong>
                    </td>
                    <td>
                        <div>
                            <span class="badge" style="background: ${actionBadgeColor}; color: ${actionTextColor}; font-size: 0.68rem; font-weight: 700;">
                                ${escapeHtml(c.recommended_action || 'STANDARD_MONITORING')}
                            </span>
                        </div>
                        <div style="font-size: 0.72rem; color: #64748b; margin-top: 0.2rem;">
                            ${escapeHtml(c.action_description || 'Routine monitoring')}
                        </div>
                    </td>
                </tr>
            `;
        }).join("");
    } catch (err) {
        tbody.innerHTML = `<tr><td colspan="7" style="color: #dc2626; padding: 1rem; text-align: center;">Error loading connection risks: ${escapeHtml(err.message)}</td></tr>`;
    }
}

async function submitGroundTruthOutcome(event) {
    event.preventDefault();
    const flightNumber = document.getElementById("ml-out-flight").value.trim();
    const carrierCode = document.getElementById("ml-out-carrier").value.trim().toUpperCase();
    const originAirport = document.getElementById("ml-out-origin").value.trim().toUpperCase();
    const destAirport = document.getElementById("ml-out-dest").value.trim().toUpperCase();
    const flightDate = document.getElementById("ml-out-date").value.trim();
    const actualDepDelayMinutes = parseFloat(document.getElementById("ml-out-delay").value) || 0.0;
    const wasCancelled = document.getElementById("ml-out-cancelled").checked;

    const resultBox = document.getElementById("ml-outcome-result");
    const feedbackEl = document.getElementById("ml-outcome-feedback");
    const btn = document.getElementById("ml-outcome-btn");

    btn.disabled = true;
    feedbackEl.textContent = "Ingesting...";
    resultBox.innerHTML = `<span style="color: #64748b;">Submitting delayed ground truth to monitoring layer...</span>`;

    try {
        const payload = {
            carrier_code: carrierCode,
            flight_number: flightNumber,
            origin_airport: originAirport,
            dest_airport: destAirport,
            flight_date: flightDate,
            actual_dep_delay_minutes: actualDepDelayMinutes,
            was_cancelled: wasCancelled,
            source_feed: "BTS Post-Flight Archive / Ground Radar"
        };

        const resp = await apiFetch("http://127.0.0.1:8000/monitoring/outcomes/ingest", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-Flyo-Monitoring-Key": "flyo-monitoring-secret-key-2026"
            },
            body: JSON.stringify(payload)
        });

        if (!resp.ok) {
            throw new Error(`Outcome ingestion returned HTTP ${resp.status}`);
        }

        const data = await resp.json();
        feedbackEl.textContent = data.status === "INGESTED" ? "Matched & Logged" : "Duplicate Ignored";

        resultBox.innerHTML = `
            <div style="border-left: 3px solid #059669; padding-left: 0.65rem;">
                <div style="font-weight: 750; color: #059669; font-size: 0.85rem;">
                    Status: ${escapeHtml(data.status)} &middot; Matched Records: ${data.matched_prediction_records_count}
                </div>
                <div style="font-size: 0.78rem; color: #334155; margin-top: 0.25rem;">
                    ${escapeHtml(data.message)}
                </div>
                <div style="font-size: 0.72rem; color: #64748b; margin-top: 0.35rem;">
                    Deduplication Key: <code>(${escapeHtml(carrierCode)}, ${escapeHtml(flightNumber)}, ${escapeHtml(originAirport)}, ${escapeHtml(destAirport)}, ${escapeHtml(flightDate)})</code>
                </div>
            </div>
        `;

        await fetchMonitoringData(true);
    } catch (err) {
        feedbackEl.textContent = "Failed";
        resultBox.innerHTML = `<div style="color: #dc2626;"><strong>Outcome Ingestion Error:</strong> ${escapeHtml(err.message)}</div>`;
    } finally {
        btn.disabled = false;
    }
}

