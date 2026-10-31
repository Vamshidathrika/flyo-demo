let currentActiveCase = null;
let lastChatCount = 0;
let currentActiveView = "monitor";

function switchSectionView(tabName) {
    currentActiveView = tabName;
    const tabs = ["monitor", "ananya", "msg", "logs"];
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
    }
}

document.addEventListener("DOMContentLoaded", () => {
    fetchBookings();
    fetchActiveCases();
    checkMockPortalStatus();
    fetchWhatsAppOperatorChat();
    fetchOtpStatus();

    // Live Polling every 2.0 seconds to keep Dashboard & WhatsApp strictly in sync
    setInterval(syncDashboard, 2000);
});

async function syncDashboard() {
    await fetchActiveCases(true);
    await fetchWhatsAppOperatorChat(true);
    await fetchOtpStatus();
}

async function fetchBookings() {
    const tbody = document.getElementById("bookings-tbody");
    try {
        const response = await fetch("/api/bookings");
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
        const res = await fetch(`/api/cases/${encodeURIComponent(caseId)}`);
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
        const response = await fetch("/api/cases");
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
        const response = await fetch("/api/cases");
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
        const response = await fetch("/api/cases");
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
        const response = await fetch(`/api/cases/simulate?bookingId=${encodeURIComponent(bookingId)}&type=CANCELLED&notifyPassenger=true`, {
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
        const response = await fetch(`/api/cases/${encodeURIComponent(caseId)}/options`);
        if (!response.ok) return;
        const options = await response.json();
        renderOptions(options);

        // Refresh active case so state badge smoothly updates to OPTIONS_READY
        const caseRes = await fetch(`/api/cases/${encodeURIComponent(caseId)}`);
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
        const response = await fetch(`/api/cases/${encodeURIComponent(caseId)}/approve`, {
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
        const response = await fetch(`/api/cases/${encodeURIComponent(targetCaseId)}/reject`, {
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
        const response = await fetch(`/api/cases/${encodeURIComponent(targetCaseId)}/rebook`, {
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
        const res = await fetch(`/api/payments/case/${encodeURIComponent(caseId)}`);
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
            if (numEl) numEl.textContent = vcc.maskedCardNumber || "4532 •••• •••• 8821";
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
        const response = await fetch(`/api/cases/${encodeURIComponent(caseId)}/audit`);
        if (!response.ok) return;
        const events = await response.json();
        renderAudit(events);
    } catch (error) {
        console.error("Error fetching audit events:", error);
    }
}

async function fetchMessages(caseId) {
    try {
        const response = await fetch(`/api/cases/${encodeURIComponent(caseId)}/messages`);
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
        const response = await fetch("/api/whatsapp/simulate-incoming", {
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
            await fetch(`/api/cases/${encodeURIComponent(caseId)}/messages`, {
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

        return `
            <div class="option-card ${cardClass}" id="option-${escapeHtml(opt.id)}">
                <div class="option-top-row">
                    <div class="rank-indicator">
                        <span class="rank-pill ${rankPillClass}">Rank ${opt.rank}</span>
                        <span class="option-flight-title">${escapeHtml(opt.flight)} &mdash; ${escapeHtml(opt.route)}</span>
                        ${connectionBadge}
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
        const res = await fetch("/api/ingest/pnr", {
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
        const res = await fetch("/api/alerts/flight-status", {
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
        const res = await fetch("/api/bookings", {
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
        await fetch(`/api/bookings/${encodeURIComponent(id)}`, { method: "DELETE" });
        await fetchBookings();
    } catch (err) {
        alert("Error deleting booking: " + err.message);
    }
}

async function clearAllBookingsPrompt() {
    if (!confirm("Clear all bookings from the database? This lets you test with ONLY your own data.")) return;
    try {
        await fetch("/api/bookings", { method: "DELETE" });
        await fetchBookings();
    } catch (err) {
        alert("Error clearing bookings: " + err.message);
    }
}

async function resetSeedBookingsPrompt(silent = false) {
    try {
        await fetch("/api/bookings/reset", { method: "POST" });
        await fetchBookings();
    } catch (err) {
        if (!silent) alert("Error resetting sample bookings: " + err.message);
    }
}

async function loadScenarioTemplate(type) {
    if (type === 'hero') {
        await fetch("/mock-airline/toggle-break?broken=false", { method: "POST" });
        await checkMockPortalStatus();
        await resetSeedBookingsPrompt(true);
        await simulateCancellation('B1');
    } else if (type === 'domestic') {
        await fetch("/mock-airline/toggle-break?broken=false", { method: "POST" });
        await checkMockPortalStatus();
        await resetSeedBookingsPrompt(true);
        await simulateCancellation('B2');
    } else if (type === 'failover') {
        await fetch("/mock-airline/toggle-break?broken=true", { method: "POST" });
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
        const response = await fetch("/api/whatsapp/messages");
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

        const response = await fetch("/api/whatsapp/simulate-incoming", {
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

function handleWhatsAppButtonClick(buttonId) {
    sendWhatsAppOperatorCommand(null, buttonId);
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
        const response = await fetch(`/api/whatsapp/otp/request?caseId=${encodeURIComponent(targetCaseId)}`, { method: "POST" });
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
        const response = await fetch("/api/whatsapp/otp/verify", {
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
        const response = await fetch("/api/whatsapp/otp/status");
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
        const response = await fetch("/mock-airline/toggle-break", { method: "POST" });
        if (!response.ok) return;
        const data = await response.json();
        updatePortalStatusUI(data.broken);
        await fetchWhatsAppOperatorChat();
    } catch (e) {
        console.error("Error toggling portal break status:", e);
    }
}

async function checkMockPortalStatus() {
    try {
        const response = await fetch("/mock-airline/break-status");
        if (!response.ok) return;
        const data = await response.json();
        updatePortalStatusUI(data.broken);
    } catch (e) {
        console.error("Error checking portal status:", e);
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
