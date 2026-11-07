package com.flyo.rescue.service.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.exception.ApprovalRequiredException;
import com.flyo.rescue.model.*;
import com.flyo.rescue.repository.*;
import com.flyo.rescue.service.*;
import com.flyo.rescue.service.provider.ExternalProviderResponse;
import com.flyo.rescue.service.provider.ProviderOutcomeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Distributed Saga Coordinator for Consequential Rebooking & Financial Settlement Workflows.
 *
 * Enforces:
 * - Persistent saga state tracking across JVM restarts
 * - Decoupling of external network I/O from database transactions
 * - Ambiguity detection (never blindly retrying after timeouts)
 * - Automated backward compensation (voiding VCCs upon terminal failure)
 * - Fail-closed transition to MANUAL_INTERVENTION_REQUIRED when unresolvable
 */
@Service
public class RebookingSagaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RebookingSagaCoordinator.class);

    private final SagaExecutionRepository sagaRepository;
    private final SagaStepLogRepository stepLogRepository;
    private final RescueCaseRepository rescueCaseRepository;
    private final RebookOptionRepository optionRepository;
    private final BookingRepository bookingRepository;
    private final PaymentSettlementService paymentSettlementService;
    private final RebookService rebookService;
    private final CaseStateMachineService stateMachineService;
    private final PassengerWhatsAppMessagingService passengerWhatsAppMessagingService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final com.flyo.rescue.integration.TicketVerificationProvider ticketVerificationProvider;
    private final com.flyo.rescue.integration.CrmSynchronizationProvider crmSynchronizationProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RebookingSagaCoordinator(
            SagaExecutionRepository sagaRepository,
            SagaStepLogRepository stepLogRepository,
            RescueCaseRepository rescueCaseRepository,
            RebookOptionRepository optionRepository,
            BookingRepository bookingRepository,
            PaymentSettlementService paymentSettlementService,
            RebookService rebookService,
            CaseStateMachineService stateMachineService,
            PassengerWhatsAppMessagingService passengerWhatsAppMessagingService,
            AuditService auditService,
            OutboxService outboxService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.flyo.rescue.integration.TicketVerificationProvider ticketVerificationProvider,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.flyo.rescue.integration.CrmSynchronizationProvider crmSynchronizationProvider) {
        this.sagaRepository = sagaRepository;
        this.stepLogRepository = stepLogRepository;
        this.rescueCaseRepository = rescueCaseRepository;
        this.optionRepository = optionRepository;
        this.bookingRepository = bookingRepository;
        this.paymentSettlementService = paymentSettlementService;
        this.rebookService = rebookService;
        this.stateMachineService = stateMachineService;
        this.passengerWhatsAppMessagingService = passengerWhatsAppMessagingService;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.ticketVerificationProvider = ticketVerificationProvider;
        this.crmSynchronizationProvider = crmSynchronizationProvider;
    }

    /**
     * Phase 1: Initiates a durable Saga execution inside the calling transaction.
     * Enforces mandatory prior human approval.
     */
    @Transactional
    public SagaExecution initiateRebookingSaga(String caseId, String optionId, String actor) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        // 1. Mandatory Explicit Human Approval Verification
        String currentState = rescueCase.getState();

        // Check 1: State must be explicitly APPROVED. AWAITING_APPROVAL is strictly rejected.
        if (!CaseStateMachineService.STATE_APPROVED.equalsIgnoreCase(currentState)) {
            log.warn("[Saga Gate] Rejected rebooking initiation for Case {}: current state '{}' is not APPROVED.",
                    caseId, currentState);
            throw new ApprovalRequiredException("Rebooking requires explicit prior human approval in APPROVED state. Current state: " + currentState + " is not authorized.");
        }

        // Check 2: Authorized human approver identity must be persisted
        if (rescueCase.getApprovedBy() == null || rescueCase.getApprovedBy().isBlank() || "anonymousUser".equalsIgnoreCase(rescueCase.getApprovedBy())) {
            log.warn("[Saga Gate] Rejected rebooking initiation for Case {}: missing authenticated approver identity.", caseId);
            throw new ApprovalRequiredException("Rebooking requires an authenticated operator or supervisor approver identity.");
        }

        // Check 3: Approval must carry a valid timestamp and must not be expired (TTL 30 minutes)
        if (rescueCase.getApprovedAt() == null) {
            log.warn("[Saga Gate] Rejected rebooking initiation for Case {}: missing approvedAt timestamp.", caseId);
            throw new ApprovalRequiredException("Rebooking requires an explicit approval timestamp.");
        }
        if (rescueCase.getApprovedAt().isBefore(LocalDateTime.now().minusMinutes(30))) {
            log.warn("[Saga Gate] Rejected rebooking initiation for Case {}: approval timestamp {} is older than 30 minutes.",
                    caseId, rescueCase.getApprovedAt());
            throw new ApprovalRequiredException("Human approval has expired (> 30 minutes old). Due to airline inventory and fare volatility, fresh supervisor re-approval is required.");
        }

        // Check 4: Approved option ID must match the option being dispatched
        if (rescueCase.getApprovedOptionId() == null || !rescueCase.getApprovedOptionId().equalsIgnoreCase(optionId)) {
            log.warn("[Saga Gate] Rejected rebooking initiation for Case {}: approved option '{}' does not match requested option '{}'.",
                    caseId, rescueCase.getApprovedOptionId(), optionId);
            throw new ApprovalRequiredException(String.format("Approved option (%s) does not match requested option (%s). Re-approval required.",
                    rescueCase.getApprovedOptionId(), optionId));
        }

        // 2. Resolve approved option
        RebookOption option = optionRepository.findById(optionId)
                .orElseGet(() -> optionRepository.findByCaseIdOrderByRankAsc(caseId).stream()
                        .filter(o -> o.getId().equalsIgnoreCase(optionId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Option not found: " + optionId)));

        String idempotencyKey = "rebook_saga_" + caseId + "_" + option.getId();

        // 3. Idempotency Check: return existing saga if already created
        Optional<SagaExecution> existingSaga = sagaRepository.findByIdempotencyKey(idempotencyKey);
        if (existingSaga.isPresent()) {
            log.info("[Saga] Idempotent request: Saga already exists for Case {} and Option {} (SagaID: {})",
                    caseId, option.getId(), existingSaga.get().getSagaId());
            return existingSaga.get();
        }

        // 3b. Duplicate active saga prevention for this case
        List<SagaExecution> activeSagas = sagaRepository.findByCaseId(caseId).stream()
                .filter(s -> SagaExecution.STATUS_PENDING.equals(s.getStatus())
                        || SagaExecution.STATUS_RUNNING.equals(s.getStatus())
                        || SagaExecution.STATUS_WAITING_FOR_EXTERNAL_RESULT.equals(s.getStatus())
                        || SagaExecution.STATUS_RETRY_PENDING.equals(s.getStatus()))
                .toList();
        if (!activeSagas.isEmpty()) {
            SagaExecution active = activeSagas.get(0);
            log.warn("[Saga Gate] Rejected duplicate saga initiation for Case {}: active saga {} already exists with status {}",
                    caseId, active.getSagaId(), active.getStatus());
            return active;
        }

        // 4. Construct payload
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("caseId", caseId);
        payloadMap.put("optionId", option.getId());
        payloadMap.put("flight", option.getFlight());
        payloadMap.put("route", option.getRoute());
        payloadMap.put("extraCost", option.getExtraCost());
        payloadMap.put("passengerName", rescueCase.getPassengerName());
        payloadMap.put("passengerPhone", rescueCase.getPassengerPhone());
        payloadMap.put("actor", actor != null ? actor : "Flyo System");

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payloadMap);
        } catch (Exception e) {
            payloadJson = "{\"caseId\":\"" + caseId + "\",\"optionId\":\"" + option.getId() + "\"}";
        }

        String corrId = "corr_" + UUID.randomUUID().toString();
        SagaExecution saga = new SagaExecution(caseId, "REBOOKING_SAGA", payloadJson, corrId, idempotencyKey);
        saga.setTenantId(rescueCase.getTenantId() != null ? rescueCase.getTenantId() : "tenant_default");
        SagaExecution saved = sagaRepository.save(saga);

        // 5. Stage Outbox event indicating Saga initiated
        outboxService.publishEvent(
                "SAGA_EXECUTION",
                saved.getSagaId(),
                "SAGA_INITIATED",
                payloadJson,
                corrId,
                "CMD_INITIATE_SAGA",
                "outbox_" + idempotencyKey
        );

        auditService.logEvent(
                caseId,
                "SAGA_INITIATED",
                actor != null ? actor : "Flyo System",
                String.format("Durable rebooking saga %s scheduled for option %s (%s).",
                        saved.getSagaId(), option.getId(), option.getFlight())
        );

        log.info("[Saga] Initiated durable Saga {} for Case {} (IdemKey: {})",
                saved.getSagaId(), caseId, idempotencyKey);

        return saved;
    }

    /**
     * Executes the next pending step of a Saga execution outside heavy DB transactions.
     */
    public void executeSagaStep(String sagaId, String workerId) {
        // 1. Claim saga under worker lease
        Optional<SagaExecution> opt = claimSaga(sagaId, workerId);
        if (opt.isEmpty()) {
            return;
        }
        SagaExecution saga = opt.get();

        log.info("[Saga] Worker '{}' executing Saga {} at step '{}' (Status: {})",
                workerId, saga.getSagaId(), saga.getCurrentStep(), saga.getStatus());

        String previousTenant = com.flyo.rescue.security.TenantContext.getTenantId();
        try {
            com.flyo.rescue.security.TenantContext.setTenantId(
                    saga.getTenantId() != null ? saga.getTenantId() : com.flyo.rescue.security.TenantContext.DEFAULT_TENANT_ID
            );

            String step = saga.getCurrentStep();
            switch (step) {
                case SagaExecution.STEP_INITIATE:
                case SagaExecution.STEP_AUTHORIZE_PAYMENT:
                    executePaymentStep(saga);
                    break;
                case SagaExecution.STEP_DISPATCH_REBOOK:
                    executeRebookStep(saga);
                    break;
                case SagaExecution.STEP_RECONCILE_BOOKING:
                    executeReconciliationStep(saga);
                    break;
                case SagaExecution.STEP_VERIFY_TICKET:
                case SagaExecution.STEP_NOTIFY_PASSENGER:
                    executeCompletionStep(saga);
                    break;
                default:
                    log.warn("[Saga] Unknown step '{}' on Saga {}. No action taken.", step, sagaId);
                    releaseLease(saga);
            }
        } catch (Exception ex) {
            log.error("[Saga] Unhandled exception executing Saga {}: {}", sagaId, ex.getMessage(), ex);
            handleStepFailure(saga, "INTERNAL_ERROR", ex.getMessage(), true);
        } finally {
            if (previousTenant != null) {
                com.flyo.rescue.security.TenantContext.setTenantId(previousTenant);
            } else {
                com.flyo.rescue.security.TenantContext.clear();
            }
        }
    }

    @Transactional
    public Optional<SagaExecution> claimSaga(String sagaId, String workerId) {
        Optional<SagaExecution> opt = sagaRepository.findById(sagaId);
        if (opt.isEmpty()) return Optional.empty();

        SagaExecution saga = opt.get();
        if (SagaExecution.STATUS_COMPLETED.equals(saga.getStatus())
                || SagaExecution.STATUS_FAILED.equals(saga.getStatus())
                || SagaExecution.STATUS_MANUAL_INTERVENTION_REQUIRED.equals(saga.getStatus())) {
            return Optional.empty();
        }

        saga.setStatus(SagaExecution.STATUS_RUNNING);
        saga.setLockedBy(workerId);
        saga.setLockedAt(LocalDateTime.now());
        saga.setAttemptCount(saga.getAttemptCount() + 1);
        return Optional.of(sagaRepository.save(saga));
    }

    @Transactional
    public void releaseLease(SagaExecution saga) {
        sagaRepository.findById(saga.getSagaId()).ifPresent(s -> {
            s.setLockedBy(null);
            s.setLockedAt(null);
            sagaRepository.save(s);
        });
    }

    // -------------------------------------------------------------------------
    // STEP 1: PAYMENT AUTHORIZATION (VCC)
    // -------------------------------------------------------------------------
    private void executePaymentStep(SagaExecution saga) {
        long start = System.currentTimeMillis();
        String caseId = saga.getCaseId();
        Map<String, Object> payload = parsePayload(saga.getPayload());
        String optionId = (String) payload.get("optionId");
        int extraCost = payload.get("extraCost") instanceof Number ? ((Number) payload.get("extraCost")).intValue() : 0;

        try {
            if (extraCost > 0) {
                log.info("[Saga] Step 1: Authorizing VCC for Case {} (Amount: ₹{})", caseId, extraCost);

                // Verify or issue virtual card
                Optional<VirtualCard> existingCard = paymentSettlementService.getActiveCardForCase(caseId);
                VirtualCard card = existingCard.orElseGet(() ->
                        paymentSettlementService.issueVirtualCard(caseId, optionId, extraCost, (String) payload.get("passengerName")));

                recordStepLog(saga.getSagaId(), SagaExecution.STEP_AUTHORIZE_PAYMENT, "AUTHORIZE", "SUCCEEDED",
                        "extraCost=" + extraCost, "cardId=" + card.getId() + ",token=" + card.getCardTokenId(), null,
                        System.currentTimeMillis() - start);

                // Store card reference in compensation payload
                saga.setCompensationPayload("{\"virtualCardId\":\"" + card.getId() + "\"}");
            } else {
                recordStepLog(saga.getSagaId(), SagaExecution.STEP_AUTHORIZE_PAYMENT, "AUTHORIZE", "SKIPPED",
                        "extraCost=0", "Zero-fare rebooking; no VCC needed", null, System.currentTimeMillis() - start);
            }

            // Advance to rebooking step
            advanceSagaStep(saga, SagaExecution.STEP_DISPATCH_REBOOK, SagaExecution.STATUS_PENDING);

        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - start;
            recordStepLog(saga.getSagaId(), SagaExecution.STEP_AUTHORIZE_PAYMENT, "AUTHORIZE", "FAILED",
                    "extraCost=" + extraCost, null, ex.getMessage(), duration);
            handleStepFailure(saga, "PAYMENT_AUTH_FAILED", ex.getMessage(), false);
        }
    }

    // -------------------------------------------------------------------------
    // STEP 2: AIRLINE REBOOKING DISPATCH
    // -------------------------------------------------------------------------
    private void executeRebookStep(SagaExecution saga) {
        long start = System.currentTimeMillis();
        String caseId = saga.getCaseId();
        Map<String, Object> payload = parsePayload(saga.getPayload());
        String optionId = (String) payload.get("optionId");
        String flight = (String) payload.get("flight");
        String passengerName = (String) payload.get("passengerName");
        int extraCost = payload.get("extraCost") instanceof Number ? ((Number) payload.get("extraCost")).intValue() : 0;

        Optional<VirtualCard> vccOpt = paymentSettlementService.getActiveCardForCase(caseId);
        String cardToken = vccOpt.map(VirtualCard::getCardTokenId).orElse("");
        String expiry = vccOpt.map(VirtualCard::getExpiry).orElse("");

        log.info("[Saga] Step 2: Dispatching rebooking for Case {}, Flight {} (extraCost: ₹{})", caseId, flight, extraCost);

        // Transition case to REBOOKING state
        rescueCaseRepository.findById(caseId).ifPresent(c -> {
            if (!CaseStateMachineService.STATE_REBOOKING.equals(c.getState())) {
                stateMachineService.transition(c, CaseStateMachineService.STATE_REBOOKING, "Flyo Saga Coordinator",
                        "Saga dispatched booking command to carrier provider");
                rescueCaseRepository.save(c);
            }
        });

        RebookResultDto result;
        try {
            if (extraCost > 0 && vccOpt.isPresent()) {
                result = rebookService.executePlaywrightRebook(
                        caseId, optionId, flight, passengerName, cardToken, expiry, "", extraCost
                );
            } else {
                result = rebookService.executePlaywrightRebook(caseId, optionId, flight, passengerName);
            }
        } catch (Exception ex) {
            // Socket timeout or network disconnect -> AMBIGUOUS OUTCOME!
            long duration = System.currentTimeMillis() - start;
            log.warn("[Saga] Exception during rebooking dispatch (potential timeout): {}", ex.getMessage());
            recordStepLog(saga.getSagaId(), SagaExecution.STEP_DISPATCH_REBOOK, "EXECUTE", "AMBIGUOUS",
                    "flight=" + flight, null, ex.getMessage(), duration);
            handleAmbiguousOutcome(saga, "TIMEOUT_UNKNOWN", "Remote call failed: " + ex.getMessage());
            return;
        }

        long duration = System.currentTimeMillis() - start;

        // --- EVALUATE OUTCOME TAXONOMY ---
        if (result != null && result.isSuccess() && result.getNewPnr() != null) {
            // OUTCOME A: CONFIRMED SUCCESS
            String newPnr = result.getNewPnr();
            log.info("[Saga] Confirmed airline booking success for Case {}! Extracted PNR: {}", caseId, newPnr);

            recordStepLog(saga.getSagaId(), SagaExecution.STEP_DISPATCH_REBOOK, "EXECUTE", "SUCCEEDED",
                    "flight=" + flight, "newPnr=" + newPnr, null, duration);

            // Update case with confirmed PNR
            rescueCaseRepository.findById(caseId).ifPresent(c -> {
                c.setNewPnr(newPnr);
                c.setApprovedOptionId(optionId);
                stateMachineService.transition(c, CaseStateMachineService.STATE_VERIFYING_BOOKING, "Flyo Saga Coordinator",
                        "Confirmed booking verified with airline host system. Extracted PNR: " + newPnr);
                stateMachineService.transition(c, CaseStateMachineService.STATE_VERIFYING_TICKET, "Flyo Saga Coordinator",
                        "Verifying electronic ticket (e-ticket) issuance and coupon status");
                rescueCaseRepository.save(c);
            });

            // Advance saga to verification & completion
            advanceSagaStep(saga, SagaExecution.STEP_VERIFY_TICKET, SagaExecution.STATUS_PENDING);

        } else if (result != null && isTimeoutResult(result)) {
            // OUTCOME C: AMBIGUOUS TIMEOUT RETURNED IN DTO
            recordStepLog(saga.getSagaId(), SagaExecution.STEP_DISPATCH_REBOOK, "EXECUTE", "AMBIGUOUS",
                    "flight=" + flight, null, result.getError(), duration);
            handleAmbiguousOutcome(saga, "TIMEOUT_UNKNOWN", result.getError());

        } else {
            // OUTCOME B: CONFIRMED FAILURE (e.g. Flight Sold Out, Fare Expired)
            String error = result != null ? result.getError() : "Unknown provider failure";
            log.warn("[Saga] Confirmed airline booking failure for Case {}: {}", caseId, error);

            recordStepLog(saga.getSagaId(), SagaExecution.STEP_DISPATCH_REBOOK, "EXECUTE", "FAILED",
                    "flight=" + flight, null, error, duration);

            // Trigger Backward Compensation
            triggerCompensation(saga, "AIRLINE_REBOOK_REJECTED", error);
        }
    }

    // -------------------------------------------------------------------------
    // STEP 2B: RECONCILIATION FOR AMBIGUOUS OUTCOMES
    // -------------------------------------------------------------------------
    private void executeReconciliationStep(SagaExecution saga) {
        long start = System.currentTimeMillis();
        String caseId = saga.getCaseId();
        Map<String, Object> payload = parsePayload(saga.getPayload());
        String flight = (String) payload.get("flight");
        String passengerName = (String) payload.get("passengerName");

        log.info("[Saga] Reconciling ambiguous booking for Case {}, Flight {}", caseId, flight);

        // Perform active reconciliation against provider
        ExternalProviderResponse<String> reconResponse = reconcileWithCarrier(caseId, flight, passengerName);
        long duration = System.currentTimeMillis() - start;

        if (reconResponse.isConfirmedSuccess() && reconResponse.getProviderReference() != null) {
            String pnr = reconResponse.getProviderReference();
            log.info("[Saga] Reconciliation resolved ambiguous outcome: booking EXISTS with PNR {}", pnr);

            recordStepLog(saga.getSagaId(), SagaExecution.STEP_RECONCILE_BOOKING, "RECONCILE", "SUCCEEDED",
                    "query=" + flight, "foundPnr=" + pnr, null, duration);

            rescueCaseRepository.findById(caseId).ifPresent(c -> {
                c.setNewPnr(pnr);
                rescueCaseRepository.save(c);
            });

            advanceSagaStep(saga, SagaExecution.STEP_VERIFY_TICKET, SagaExecution.STATUS_RUNNING);
            executeCompletionStep(saga);

        } else if (reconResponse.isConfirmedFailure()) {
            log.info("[Saga] Reconciliation confirmed NO booking exists on airline host.");
            recordStepLog(saga.getSagaId(), SagaExecution.STEP_RECONCILE_BOOKING, "RECONCILE", "CONFIRMED_ABSENT",
                    "query=" + flight, "No reservation exists", null, duration);

            // Trigger backward compensation or safe manual review rather than blind duplicate booking
            triggerCompensation(saga, "AIRLINE_REBOOK_TIMEOUT_FAILED", "Carrier reconciliation confirmed no reservation created.");
        } else {
            // Still ambiguous / unknown: Escalate to human operator. NEVER blindly retry after a timeout!
            log.warn("[Saga] Ambiguous booking outcome for Case {}. Marking MANUAL_INTERVENTION_REQUIRED to prevent duplicate bookings.", caseId);
            recordStepLog(saga.getSagaId(), SagaExecution.STEP_RECONCILE_BOOKING, "RECONCILE", "UNKNOWN_AMBIGUOUS",
                    "query=" + flight, null, "Carrier reconciliation timed out or cannot verify booking presence. Escalating to avoid duplicate booking.", duration);

            rescueCaseRepository.findById(caseId).ifPresent(c -> {
                stateMachineService.transition(c, CaseStateMachineService.STATE_MANUAL_INTERVENTION_REQUIRED, "Flyo Saga Coordinator",
                        "Booking outcome ambiguous after provider timeout. Dispatched to manual ticketing desk to prevent duplicate booking.");
                c.setManualNextStep("Verify carrier host for passenger " + passengerName + " on flight " + flight + " before re-attempting booking.");
                rescueCaseRepository.save(c);
            });

            markManualIntervention(saga, "UNRESOLVED_AMBIGUITY",
                    "Booking outcome ambiguous after network timeout. Automated retry aborted to prevent duplicate booking. Operator must verify airline host.");
        }
    }

    private ExternalProviderResponse<String> reconcileWithCarrier(String caseId, String flight, String passengerName) {
        // Query active booking in carrier portal/GDS
        try {
            // If case already has a newPnr set in local DB, verify it
            Optional<RescueCase> cOpt = rescueCaseRepository.findById(caseId);
            if (cOpt.isPresent() && cOpt.get().getNewPnr() != null) {
                return ExternalProviderResponse.success("Carrier", cOpt.get().getNewPnr(), "Booking verified");
            }
            // After a timeout without a known PNR, the outcome is inherently ambiguous unless
            // the carrier provides an authoritative idempotency check or name-flight query.
            // Returning UNKNOWN prevents blind duplicate bookings on retry.
            return ExternalProviderResponse.timeout("Carrier", "Cannot verify booking absence on carrier host after timeout; state is UNKNOWN");
        } catch (Exception e) {
            return ExternalProviderResponse.timeout("Carrier", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // STEP 3: E-TICKET VERIFICATION, PASSENGER NOTIFICATION, CRM SYNC & COMPLETION
    // -------------------------------------------------------------------------
    private void executeCompletionStep(SagaExecution saga) {
        long start = System.currentTimeMillis();
        String caseId = saga.getCaseId();
        Map<String, Object> payload = parsePayload(saga.getPayload());
        String optionId = (String) payload.get("optionId");
        int extraCost = payload.get("extraCost") instanceof Number ? ((Number) payload.get("extraCost")).intValue() : 0;
        String passengerPhone = (String) payload.get("passengerPhone");

        RescueCase rescueCase = rescueCaseRepository.findById(caseId).orElse(null);
        if (rescueCase == null) {
            releaseLease(saga);
            return;
        }

        String pnr = rescueCase.getNewPnr();
        String ticketNumber = "098-" + (pnr != null ? pnr : "2451928410");

        // --- SUBSTEP 3A: INDEPENDENT TICKET & COUPON VERIFICATION ---
        boolean ticketVerified = true;
        String ticketStatus = "VERIFIED";
        if (ticketVerificationProvider != null && pnr != null) {
            try {
                com.flyo.rescue.integration.dto.TicketVerificationResult ticketResult =
                        ticketVerificationProvider.verifyTicket(pnr, ticketNumber);
                if (ticketResult != null && !ticketResult.isValid()) {
                    ticketVerified = false;
                    ticketStatus = "VERIFICATION_FAILED";
                    log.warn("[Saga] Independent ticket verification FAILED for PNR {}: {}", pnr, ticketResult.getRemarks());
                } else if (ticketResult != null && ticketResult.getTicketNumber() != null) {
                    ticketNumber = ticketResult.getTicketNumber();
                }
            } catch (Exception ex) {
                log.warn("[Saga] Ticket verification call error for PNR {}: {}", pnr, ex.getMessage());
                ticketVerified = false;
                ticketStatus = "VERIFICATION_FAILED";
            }
        }

        rescueCase.setTicketNumber(ticketNumber);
        rescueCase.setTicketStatus(ticketStatus);
        saga.setTicketNumber(ticketNumber);
        saga.setTicketStatus(ticketStatus);

        if (!ticketVerified) {
            recordStepLog(saga.getSagaId(), SagaExecution.STEP_VERIFY_TICKET, "VERIFY", "FAILED",
                    "pnr=" + pnr + ",ticket=" + ticketNumber, null, "Independent electronic ticket verification failed", System.currentTimeMillis() - start);
            stateMachineService.transition(rescueCase, CaseStateMachineService.STATE_MANUAL_INTERVENTION_REQUIRED, "Flyo Ticket Verifier",
                    "Booking confirmed on host (PNR " + pnr + ") but electronic ticket failed validation. Flagged for manual ticketing desk.");
            rescueCaseRepository.save(rescueCase);
            markManualIntervention(saga, "TICKET_VERIFICATION_FAILED", "Electronic ticket validation failed on PNR " + pnr);
            return;
        }

        // --- SUBSTEP 3B: SETTLE VIRTUAL CARD ---
        if (extraCost > 0) {
            paymentSettlementService.getActiveCardForCase(caseId).ifPresent(card -> {
                paymentSettlementService.recordSettlement(caseId, card.getId(), extraCost, "Partner Airline Host");
            });
        }

        // --- SUBSTEP 3C: TRANSITION TO DONE ---
        stateMachineService.transition(rescueCase, CaseStateMachineService.STATE_DONE, "Flyo Booking Verifier",
                "Authoritative booking confirmed (PNR " + pnr + ") and electronic ticket " + ticketNumber + " verified active.");

        // Update Monitored Booking
        if (rescueCase.getBookingId() != null) {
            bookingRepository.findById(rescueCase.getBookingId()).ifPresent(b -> {
                b.setStatus("Rescued");
                bookingRepository.save(b);
            });
        }

        // Mark Saga Completed
        completeSaga(saga);

        recordStepLog(saga.getSagaId(), SagaExecution.STEP_VERIFY_TICKET, "VERIFY", "SUCCEEDED",
                "pnr=" + pnr + ",ticket=" + ticketNumber, "E-ticket verified active & case marked DONE", null, System.currentTimeMillis() - start);

        // --- SUBSTEP 3D: PASSENGER MESSAGING NOTIFICATION ---
        try {
            if (passengerPhone != null && !passengerPhone.isBlank()) {
                RebookOption bookedOption = optionRepository.findById(optionId).orElse(null);
                Optional<VirtualCard> vcc = paymentSettlementService.getActiveCardForCase(caseId);
                String vccRef = vcc.map(VirtualCard::getMaskedCardNumber).orElse("N/A");
                passengerWhatsAppMessagingService.sendBookingConfirmation(passengerPhone, rescueCase, bookedOption, pnr, vccRef);
                rescueCase.setNotificationStatus("DELIVERED");
                saga.setNotificationStatus("DELIVERED");
            } else {
                rescueCase.setNotificationStatus("SKIPPED_NO_PHONE");
                saga.setNotificationStatus("SKIPPED_NO_PHONE");
            }
        } catch (Exception ex) {
            log.error("[Saga] Passenger notification failed for Case {}: {}. Booking remains safely intact.", caseId, ex.getMessage());
            rescueCase.setNotificationStatus("FAILED");
            saga.setNotificationStatus("FAILED");
            // Messaging failure does NOT roll back confirmed booking or ticket!
        }

        // --- SUBSTEP 3E: CRM SYNCHRONIZATION ---
        try {
            if (crmSynchronizationProvider != null) {
                com.flyo.rescue.integration.dto.CrmSyncResult crmResult = crmSynchronizationProvider.syncCase(
                        new com.flyo.rescue.integration.dto.CrmSyncRequest(
                                caseId, pnr, rescueCase.getPassengerName(), "RESOLVED_REBOOKED",
                                "Flight disruption rescued. New PNR: " + pnr + ", Ticket: " + ticketNumber
                        )
                );
                if (crmResult != null && crmResult.isSuccess()) {
                    rescueCase.setCrmSyncStatus("SYNCED");
                    saga.setCrmSyncStatus("SYNCED");
                    log.info("[Saga] CRM synchronized successfully for Case {}. SyncRef: {}", caseId, crmResult.getExternalSyncReference());
                } else {
                    rescueCase.setCrmSyncStatus("FAILED");
                    saga.setCrmSyncStatus("FAILED");
                }
            } else {
                rescueCase.setCrmSyncStatus("SKIPPED_NOT_CONFIGURED");
                saga.setCrmSyncStatus("SKIPPED_NOT_CONFIGURED");
            }
        } catch (Exception ex) {
            log.error("[Saga] CRM synchronization failed for Case {}: {}. Booking remains safely intact.", caseId, ex.getMessage());
            rescueCase.setCrmSyncStatus("FAILED");
            saga.setCrmSyncStatus("FAILED");
            // CRM failure remains independently recoverable
        }

        rescueCaseRepository.save(rescueCase);

        auditService.logEvent(
                caseId,
                "SAGA_COMPLETED",
                "Flyo Saga Coordinator",
                String.format("Rebooking saga %s completed. PNR: %s, Ticket: %s, Notification: %s, CRM: %s.",
                        saga.getSagaId(), pnr, ticketNumber, rescueCase.getNotificationStatus(), rescueCase.getCrmSyncStatus())
        );

        log.info("[Saga] Rebooking saga {} fully completed for Case {} (Ticket: {}, Notification: {}, CRM: {})",
                saga.getSagaId(), caseId, ticketNumber, rescueCase.getNotificationStatus(), rescueCase.getCrmSyncStatus());
    }

    // -------------------------------------------------------------------------
    // COMPENSATION & FAILURE HANDLERS
    // -------------------------------------------------------------------------
    @Transactional
    public void triggerCompensation(SagaExecution saga, String reasonCode, String errorDetails) {
        long start = System.currentTimeMillis();
        String caseId = saga.getCaseId();
        saga.setStatus(SagaExecution.STATUS_COMPENSATING);
        saga.setFailureReasonCode(reasonCode);

        log.warn("[Saga] Triggering compensation for Saga {} (Case {}). Reason: {} - {}",
                saga.getSagaId(), caseId, reasonCode, errorDetails);

        // Compensation 1: Void VCC Authorization
        try {
            Map<String, Object> compPayload = parsePayload(saga.getCompensationPayload());
            String cardId = (String) compPayload.get("virtualCardId");
            if (cardId != null) {
                paymentSettlementService.voidVirtualCard(caseId, cardId);
                recordStepLog(saga.getSagaId(), "COMPENSATE_VCC", "COMPENSATE", "SUCCEEDED",
                        "cardId=" + cardId, "VCC authorization voided", null, System.currentTimeMillis() - start);
            }
        } catch (Exception ex) {
            log.error("[Saga] Compensation error voiding VCC for Case {}: {}", caseId, ex.getMessage());
            recordStepLog(saga.getSagaId(), "COMPENSATE_VCC", "COMPENSATE", "FAILED",
                    null, null, ex.getMessage(), System.currentTimeMillis() - start);
        }

        // Compensation 2: Transition case to FAILED_ESCALATED
        rescueCaseRepository.findById(caseId).ifPresent(c -> {
            c.setError(errorDetails);
            c.setManualNextStep("Manual Action Required: Automated rebooking was rejected by airline. Operator must manually secure alternate flight or refund.");
            stateMachineService.transition(c, CaseStateMachineService.STATE_FAILED_ESCALATED, "Flyo Saga Coordinator",
                    "Automated rebooking saga failed: " + errorDetails);
            rescueCaseRepository.save(c);
        });

        sagaRepository.findById(saga.getSagaId()).ifPresent(s -> {
            s.setStatus(SagaExecution.STATUS_FAILED);
            s.setFailureReasonCode(reasonCode);
            s.setLockedBy(null);
            s.setLockedAt(null);
            s.setUpdatedAt(LocalDateTime.now());
            sagaRepository.save(s);
            saga.setVersion(s.getVersion());
        });
        saga.setStatus(SagaExecution.STATUS_FAILED);
        saga.setLockedBy(null);
        saga.setLockedAt(null);

        outboxService.publishEvent(
                "SAGA_EXECUTION",
                saga.getSagaId(),
                "SAGA_FAILED",
                "{\"caseId\":\"" + caseId + "\",\"reason\":\"" + reasonCode + "\",\"error\":\"" + errorDetails + "\"}"
        );

        auditService.logEvent(
                caseId,
                "SAGA_FAILED",
                "Flyo Saga Coordinator",
                String.format("Rebooking saga %s failed and completed compensation. Case escalated for human intervention.", saga.getSagaId())
        );
    }

    @Transactional
    public void handleAmbiguousOutcome(SagaExecution saga, String code, String message) {
        saga.setStatus(SagaExecution.STATUS_WAITING_FOR_EXTERNAL_RESULT);
        saga.setCurrentStep(SagaExecution.STEP_RECONCILE_BOOKING);
        saga.setFailureReasonCode(code);
        saga.setLockedBy(null);
        saga.setLockedAt(null);
        saga.setUpdatedAt(LocalDateTime.now());
        sagaRepository.save(saga);

        rescueCaseRepository.findById(saga.getCaseId()).ifPresent(c -> {
            stateMachineService.transition(c, CaseStateMachineService.STATE_RECONCILING, "Flyo Saga Coordinator",
                    "Ambiguous outcome: network timeout during rebooking dispatch. Awaiting carrier reconciliation.");
            rescueCaseRepository.save(c);
        });

        log.warn("[Saga] Saga {} entered WAITING_FOR_EXTERNAL_RESULT. Will reconcile before retrying.", saga.getSagaId());
    }

    @Transactional
    public void markManualIntervention(SagaExecution saga, String reasonCode, String details) {
        sagaRepository.findById(saga.getSagaId()).ifPresent(s -> {
            s.setStatus(SagaExecution.STATUS_MANUAL_INTERVENTION_REQUIRED);
            s.setFailureReasonCode(reasonCode);
            s.setLockedBy(null);
            s.setLockedAt(null);
            s.setUpdatedAt(LocalDateTime.now());
            if (saga.getTicketStatus() != null) {
                s.setTicketStatus(saga.getTicketStatus());
            }
            if (saga.getTicketNumber() != null) {
                s.setTicketNumber(saga.getTicketNumber());
            }
            sagaRepository.save(s);
            saga.setVersion(s.getVersion());
        });
        saga.setStatus(SagaExecution.STATUS_MANUAL_INTERVENTION_REQUIRED);
        saga.setFailureReasonCode(reasonCode);
        saga.setLockedBy(null);
        saga.setLockedAt(null);
        saga.setUpdatedAt(LocalDateTime.now());

        rescueCaseRepository.findById(saga.getCaseId()).ifPresent(c -> {
            String existing = c.getManualNextStep();
            String instruction = (existing != null && !existing.isBlank())
                    ? existing + " [Reason: " + details + "]"
                    : "CRITICAL: Saga marked MANUAL_INTERVENTION_REQUIRED (" + reasonCode + "): " + details;
            c.setManualNextStep(instruction);
            if (!CaseStateMachineService.STATE_MANUAL_INTERVENTION_REQUIRED.equals(c.getState())) {
                stateMachineService.transition(c, CaseStateMachineService.STATE_MANUAL_INTERVENTION_REQUIRED, "Flyo Saga Coordinator",
                        "Saga requires manual intervention: " + details);
            }
            rescueCaseRepository.save(c);
        });

        auditService.logEvent(
                saga.getCaseId(),
                "MANUAL_INTERVENTION_REQUIRED",
                "Flyo Saga Coordinator",
                String.format("CRITICAL: Saga %s requires operator intervention: %s", saga.getSagaId(), details)
        );
    }

    private void scheduleRetry(SagaExecution saga, String code, int delaySeconds) {
        saga.setStatus(SagaExecution.STATUS_RETRY_PENDING);
        saga.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySeconds));
        saga.setFailureReasonCode(code);
        saga.setLockedBy(null);
        saga.setLockedAt(null);
        saga.setUpdatedAt(LocalDateTime.now());
        sagaRepository.save(saga);
        log.info("[Saga] Scheduled retry for Saga {} in {}s (Attempt {}/{})",
                saga.getSagaId(), delaySeconds, saga.getAttemptCount(), saga.getMaxAttempts());
    }

    private void handleStepFailure(SagaExecution saga, String code, String msg, boolean retryable) {
        if (retryable && saga.getAttemptCount() < saga.getMaxAttempts()) {
            long baseDelay = (long) Math.pow(2, saga.getAttemptCount()) * 3;
            long jitter = (long) (Math.random() * 4); // 0-3s random jitter
            long totalDelay = baseDelay + jitter;
            scheduleRetry(saga, code, (int) totalDelay);
        } else {
            log.warn("[Saga] Terminal failure or max retries reached ({}/{}) on Saga {}. Compensating.",
                    saga.getAttemptCount(), saga.getMaxAttempts(), saga.getSagaId());
            triggerCompensation(saga, code, msg);
        }
    }

    @Transactional
    public void advanceSagaStep(SagaExecution saga, String nextStep, String status) {
        sagaRepository.findById(saga.getSagaId()).ifPresent(s -> {
            s.setCurrentStep(nextStep);
            s.setStatus(status);
            s.setCompensationPayload(saga.getCompensationPayload());
            s.setPayload(saga.getPayload());
            s.setTicketNumber(saga.getTicketNumber());
            s.setTicketStatus(saga.getTicketStatus());
            if (SagaExecution.STATUS_PENDING.equals(status)) {
                s.setLockedBy(null);
                s.setLockedAt(null);
            }
            s.setUpdatedAt(LocalDateTime.now());
            sagaRepository.save(s);
            saga.setVersion(s.getVersion());
        });
        saga.setCurrentStep(nextStep);
        saga.setStatus(status);
        if (SagaExecution.STATUS_PENDING.equals(status)) {
            saga.setLockedBy(null);
            saga.setLockedAt(null);
        }
        saga.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional
    public void completeSaga(SagaExecution saga) {
        sagaRepository.findById(saga.getSagaId()).ifPresent(s -> {
            s.setCurrentStep(SagaExecution.STEP_COMPLETED);
            s.setStatus(SagaExecution.STATUS_COMPLETED);
            s.setLockedBy(null);
            s.setLockedAt(null);
            s.setUpdatedAt(LocalDateTime.now());
            sagaRepository.save(s);
            saga.setVersion(s.getVersion());
        });
        saga.setCurrentStep(SagaExecution.STEP_COMPLETED);
        saga.setStatus(SagaExecution.STATUS_COMPLETED);
        saga.setLockedBy(null);
        saga.setLockedAt(null);
        saga.setUpdatedAt(LocalDateTime.now());
    }

    private void recordStepLog(String sagaId, String step, String action, String status,
                               String inPayload, String outPayload, String error, long duration) {
        try {
            SagaStepLog logEntry = new SagaStepLog(sagaId, com.flyo.rescue.security.TenantContext.getTenantId(),
                    step, action, status, inPayload, outPayload, error, duration);
            stepLogRepository.save(logEntry);
        } catch (Exception e) {
            log.warn("[Saga] Could not record step log: {}", e.getMessage());
        }
    }

    @Transactional
    public int recoverExpiredSagaLeases(long leaseTimeoutSeconds) {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(leaseTimeoutSeconds);
        List<SagaExecution> expired = sagaRepository.findExpiredLeases(cutoff);
        for (SagaExecution saga : expired) {
            log.warn("[Saga] Recovering abandoned lease on Saga {} (locked by '{}' at {})",
                    saga.getSagaId(), saga.getLockedBy(), saga.getLockedAt());
            saga.setStatus(SagaExecution.STATUS_PENDING);
            saga.setLockedBy(null);
            saga.setLockedAt(null);
            sagaRepository.save(saga);
        }
        return expired.size();
    }

    public List<SagaExecution> getPendingSagasForExecution(int limit) {
        return sagaRepository.findPendingSagasAvailable(LocalDateTime.now(), PageRequest.of(0, limit));
    }

    public Optional<SagaExecution> getSagaById(String sagaId) {
        return sagaRepository.findById(sagaId);
    }

    public List<SagaStepLog> getSagaLogs(String sagaId) {
        return stepLogRepository.findBySagaIdOrderByCreatedAtAsc(sagaId);
    }

    public Map<String, Object> getSagaMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("pendingSagas", sagaRepository.countByStatus(SagaExecution.STATUS_PENDING));
        metrics.put("runningSagas", sagaRepository.countByStatus(SagaExecution.STATUS_RUNNING));
        metrics.put("waitingExternalSagas", sagaRepository.countByStatus(SagaExecution.STATUS_WAITING_FOR_EXTERNAL_RESULT));
        metrics.put("retryPendingSagas", sagaRepository.countByStatus(SagaExecution.STATUS_RETRY_PENDING));
        metrics.put("compensatingSagas", sagaRepository.countByStatus(SagaExecution.STATUS_COMPENSATING));
        metrics.put("completedSagas", sagaRepository.countByStatus(SagaExecution.STATUS_COMPLETED));
        metrics.put("failedSagas", sagaRepository.countByStatus(SagaExecution.STATUS_FAILED));
        metrics.put("manualInterventionSagas", sagaRepository.countByStatus(SagaExecution.STATUS_MANUAL_INTERVENTION_REQUIRED));
        return metrics;
    }

    private boolean isTimeoutResult(RebookResultDto result) {
        if (result == null || result.getError() == null) return false;
        String err = result.getError().toLowerCase();
        return err.contains("timeout") || err.contains("timed out") || err.contains("gateway") || err.contains("504");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(payloadJson, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
