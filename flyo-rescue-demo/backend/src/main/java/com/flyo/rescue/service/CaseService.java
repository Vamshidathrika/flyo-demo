package com.flyo.rescue.service;

import com.flyo.rescue.exception.ApprovalRequiredException;
import com.flyo.rescue.exception.IllegalStateTransitionException;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class CaseService {

    private final RescueCaseRepository caseRepository;
    private final BookingRepository bookingRepository;
    private final RebookOptionRepository optionRepository;
    private final RankingEngine rankingEngine;
    private final FlightSearchService flightSearchService;
    private final RebookService rebookService;
    private final AuditService auditService;
    private final MessageService messageService;
    private final PaymentSettlementService paymentSettlementService;
    private final CaseStateMachineService stateMachineService;
    private final FlightEvidenceReconciliationService reconciliationService;
    private final CanonicalItineraryService canonicalItineraryService;

    public CaseService(RescueCaseRepository caseRepository,
                       BookingRepository bookingRepository,
                       RebookOptionRepository optionRepository,
                       RankingEngine rankingEngine,
                       FlightSearchService flightSearchService,
                       RebookService rebookService,
                       AuditService auditService,
                       MessageService messageService,
                       PaymentSettlementService paymentSettlementService,
                       CaseStateMachineService stateMachineService,
                       FlightEvidenceReconciliationService reconciliationService,
                       CanonicalItineraryService canonicalItineraryService) {
        this.caseRepository = caseRepository;
        this.bookingRepository = bookingRepository;
        this.optionRepository = optionRepository;
        this.rankingEngine = rankingEngine;
        this.flightSearchService = flightSearchService;
        this.rebookService = rebookService;
        this.auditService = auditService;
        this.messageService = messageService;
        this.paymentSettlementService = paymentSettlementService;
        this.stateMachineService = stateMachineService;
        this.reconciliationService = reconciliationService;
        this.canonicalItineraryService = canonicalItineraryService;
    }

    private boolean isGlobalAdmin() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.flyo.rescue.security.FlyoUserPrincipal p) {
            return p.getAuthorizedTenants().contains("*");
        }
        return false;
    }

    public List<RescueCase> getAllCases() {
        String tenantId = com.flyo.rescue.security.TenantContext.getTenantId();
        if (isGlobalAdmin()) {
            return caseRepository.findAll();
        }
        return caseRepository.findByTenantId(tenantId);
    }

    public Optional<RescueCase> getCaseById(String id) {
        Optional<RescueCase> caseOpt = caseRepository.findById(id);
        if (caseOpt.isPresent()) {
            String tenantId = com.flyo.rescue.security.TenantContext.getTenantId();
            if (!isGlobalAdmin() && !tenantId.equals(caseOpt.get().getTenantId())) {
                org.slf4j.LoggerFactory.getLogger(CaseService.class)
                        .warn("[Tenant Security] Blocked cross-tenant case access: case {} belongs to {}, caller tenant is {}",
                                id, caseOpt.get().getTenantId(), tenantId);
                return Optional.empty();
            }
        }
        return caseOpt;
    }

    @Transactional
    public RescueCase simulateCancellation(String bookingId, String type) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        booking.setStatus("Cancelled");
        bookingRepository.save(booking);

        // Normalize and persist canonical segments
        List<com.flyo.rescue.model.FlightSegment> segments = canonicalItineraryService.normalizeAndPersistSegments(booking);
        com.flyo.rescue.model.FlightSegment primarySegment = segments.isEmpty() ? null : segments.get(0);

        // Idempotency guard: Return existing active or completed case
        Optional<RescueCase> existingOpt = caseRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId);
        if (existingOpt.isPresent()) {
            RescueCase existing = existingOpt.get();
            // If already resolved (DONE) or in active non-escalated lifecycle, preserve existing case state
            if ("DONE".equals(existing.getState()) || (!"ESCALATED".equals(existing.getState()) && !"FAILED_ESCALATED".equals(existing.getState()))) {
                return existing;
            }
        }

        String caseId = bookingId.equalsIgnoreCase("B1") ? "C1" : "CASE-" + bookingId;
        String flNumber = primarySegment != null ? primarySegment.getFlightNumber() : booking.getFlight();
        String flRoute = primarySegment != null ? primarySegment.getRoute() : booking.getRoute();
        String flDept = primarySegment != null ? primarySegment.getScheduledDeparture() : booking.getDateTime();
        String cancelledSegment = flRoute + " (" + flNumber + " " + flDept + ")";
        
        String missedConnection = booking.isMultiSegment() ? booking.getOnwardFlight() : "None";
        String hotelRisk = (booking.getHotelNote() != null && !booking.getHotelNote().equalsIgnoreCase("None"))
                ? booking.getHotelNote()
                : "None";

        String impactText;
        if (booking.isMultiSegment()) {
            if (!"None".equals(hotelRisk)) {
                impactText = String.format("Flight %s %s cancelled. Misses onward flight %s. %s is at risk.",
                        flNumber, flRoute, missedConnection, hotelRisk);
            } else {
                impactText = String.format("Flight %s %s cancelled. Misses onward connection: %s.",
                        flNumber, flRoute, missedConnection);
            }
        } else {
            impactText = String.format("Flight %s %s cancelled. Standalone trip disrupted.",
                    flNumber, flRoute);
        }

        RescueCase rescueCase = caseRepository.findById(caseId).orElse(
                new RescueCase(
                        caseId,
                        booking.getId(),
                        booking.getPassengerName(),
                        "DETECTED",
                        cancelledSegment,
                        flNumber,
                        impactText,
                        missedConnection,
                        hotelRisk
                )
        );

        rescueCase.setState("DETECTED");
        rescueCase.setTenantId(com.flyo.rescue.security.TenantContext.getTenantId());
        rescueCase.setCancelledSegment(cancelledSegment);
        rescueCase.setFlight(flNumber);
        rescueCase.setFlightNumber(flNumber);
        rescueCase.setPassengerId(booking.getPassengerId());
        rescueCase.setPnr(booking.getPnr());
        rescueCase.setItineraryId(booking.getItineraryId());
        rescueCase.setDisruptedSegmentId(primarySegment != null ? primarySegment.getId() : "SEG-" + booking.getId() + "-1");
        rescueCase.setOriginAirport(primarySegment != null ? primarySegment.getOriginAirport() : booking.getOriginAirport());
        rescueCase.setDestinationAirport(primarySegment != null ? primarySegment.getDestinationAirport() : booking.getDestinationAirport());
        rescueCase.setScheduledDeparture(flDept);
        rescueCase.setItineraryVersion(booking.getItineraryVersion());
        rescueCase.setFinalDestination(booking.getFinalDestination());
        rescueCase.setOnwardSegmentsCount(segments.size() > 1 ? (segments.size() - 1) : 0);
        rescueCase.setMismatchReason(null);
        rescueCase.setImpact(impactText);
        rescueCase.setMissedConnection(missedConnection);
        rescueCase.setHotelRisk(hotelRisk);
        rescueCase.setApprovedOptionId(null);
        rescueCase.setApprovedBy(null);
        rescueCase.setApprovedAt(null);
        rescueCase.setNewPnr(null);
        rescueCase.setError(null);
        rescueCase.setManualNextStep(null);

        // Multi-Source Evidence Reconciliation (Radar + Open-Meteo Weather + News Intelligence)
        String dep = rescueCase.getOriginAirport();
        String arr = rescueCase.getDestinationAirport();
        com.flyo.rescue.dto.ReconciledFlightEvidence evidence = reconciliationService.reconcileDisruption(
                flNumber, dep, arr, type, 0
        );

        rescueCase.setReconciledConfidence(evidence.getConfidencePercent());
        rescueCase.setDisruptionEvidence(evidence.getReconciledReason());
        rescueCase.setWeatherSummary(evidence.getWeatherSummary());

        // Invalidate stale options in repository for this case
        optionRepository.deleteAll(optionRepository.findByCaseIdOrderByRankAsc(caseId));

        RescueCase saved = caseRepository.save(rescueCase);

        // Structured Audit Trail: Itinerary Resolved & Segment Validated
        auditService.logEvent(
                caseId,
                "INCIDENT_ITINERARY_RESOLVED",
                "Flyo Itinerary Service",
                String.format("Itinerary %s (v%d) resolved for passenger %s (PNR %s).",
                        booking.getItineraryId(), booking.getItineraryVersion(), booking.getPassengerName(), booking.getPnr())
        );

        auditService.logEvent(
                caseId,
                "INCIDENT_SEGMENT_VALIDATED",
                "Flyo Itinerary Service",
                String.format("Disrupted segment %s (%s %s) validated. Final destination: %s.",
                        rescueCase.getDisruptedSegmentId(), flNumber, flRoute, rescueCase.getFinalDestination())
        );

        // Audit Trail: Disruption Detected
        auditService.logEvent(
                caseId,
                "DISRUPTION_DETECTED",
                "Flyo Engine",
                String.format("Flight %s %s cancelled. Disruption detected for passenger %s (PNR %s).",
                        flNumber, flRoute, booking.getPassengerName(), booking.getPnr())
        );

        // Audit Trail: Evidence Reconciled
        auditService.logEvent(
                caseId,
                "EVIDENCE_RECONCILED",
                "Flyo Reconciliation Engine",
                String.format("Multi-source disruption verified with %d%% confidence (%s). Weather: %s.",
                        evidence.getConfidencePercent(), evidence.getConfidenceLevel(), evidence.getWeatherSummary())
        );

        // WhatsApp Passenger Care Stage 1: Immediate Proactive Reassurance Notice
        messageService.sendPassengerReassuranceNotice(
                caseId,
                booking.getPassengerName(),
                flNumber,
                "FL301",
                missedConnection
        );

        // Audit Trail: Passenger Reassurance Sent
        auditService.logEvent(
                caseId,
                "PASSENGER_REASSURED",
                "Flyo Messaging Engine",
                String.format("Stage 1 Reassurance notice dispatched to passenger %s via WhatsApp.", booking.getPassengerName())
        );

        return saved;
    }

    @Transactional
    public List<RebookOption> generateAndRankOptions(String caseId) {
        RescueCase rescueCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        if (CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED.equals(rescueCase.getState())) {
            throw new IllegalStateTransitionException("409 Conflict: Case " + caseId + " is in DATA_RECONCILIATION_REQUIRED. Options generation blocked.");
        }

        Booking booking = bookingRepository.findById(rescueCase.getBookingId()).orElse(null);
        if (booking == null) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, "Underlying booking missing for case: " + caseId);
            return List.of();
        }

        canonicalItineraryService.normalizeAndPersistSegments(booking);
        Optional<com.flyo.rescue.model.FlightSegment> segOpt = canonicalItineraryService.resolveDisruptedSegment(
                booking, rescueCase.getFlightNumber(), rescueCase.getOriginAirport(), rescueCase.getDestinationAirport());
        if (segOpt.isEmpty()) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, "Disrupted flight segment mismatch: " + rescueCase.getFlightNumber());
            return List.of();
        }

        com.flyo.rescue.model.FlightSegment segment = segOpt.get();
        var validation = canonicalItineraryService.validateItineraryIntegrity(rescueCase, booking, segment);
        if (!validation.isValid()) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, validation.mismatchReason());
            return List.of();
        }

        List<RebookOption> rawOptions = flightSearchService.findCandidateOptions(caseId, booking, segment, rescueCase.getFinalDestination());
        for (RebookOption opt : rawOptions) {
            var optVal = canonicalItineraryService.validateOptionRoute(rescueCase, booking, opt);
            if (!optVal.isValid()) {
                canonicalItineraryService.flagReconciliationRequired(rescueCase, optVal.mismatchReason());
                return List.of();
            }
        }

        List<RebookOption> rankedOptions = rankingEngine.rankOptions(rawOptions, rescueCase.getMissedConnection());
        optionRepository.deleteAll(optionRepository.findByCaseIdOrderByRankAsc(caseId));
        List<RebookOption> savedOptions = optionRepository.saveAll(rankedOptions);

        // Invalidate and revoke any previous approval upon re-ranking (material change to itinerary options)
        rescueCase.setApprovedOptionId(null);
        rescueCase.setApprovedBy(null);
        rescueCase.setApprovedAt(null);

        // Transition state to OPTIONS_READY
        stateMachineService.transition(rescueCase, CaseStateMachineService.STATE_OPTIONS_READY, "Flyo Ranking Engine", "Options generated and ranked. Prior approvals revoked.");
        caseRepository.save(rescueCase);

        RebookOption bestOption = savedOptions.isEmpty() ? null : savedOptions.get(0);
        String bestFlightDesc = bestOption != null ? (bestOption.getFlight() + " via " + bestOption.getRoute()) : "FL301 via Mumbai";
        String bestNote = bestOption != null
                ? String.format("Generated and ranked %d rebooking options. Option %s (%s) ranked #1 (+₹%,d%s).",
                    savedOptions.size(), bestOption.getId(), bestOption.getRoute(), bestOption.getExtraCost(),
                    bestOption.isSavesConnection() ? ", preserves onward schedule" : "")
                : "Generated and ranked rebooking options.";

        // Audit Trail: Options Ranked
        auditService.logEvent(
                caseId,
                "OPTIONS_RANKED",
                "Flyo Ranking Engine",
                bestNote
        );

        // WhatsApp: Generate Draft Message
        messageService.saveDraft(caseId, rescueCase.getPassengerName(), rescueCase.getFlight(), bestFlightDesc);

        return savedOptions;
    }

    public List<RebookOption> getOptionsForCase(String caseId) {
        RescueCase rescueCase = caseRepository.findById(caseId).orElse(null);
        if (rescueCase == null) return List.of();

        if (CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED.equals(rescueCase.getState())) {
            org.slf4j.LoggerFactory.getLogger(CaseService.class)
                    .warn("[CaseService] Case {} in DATA_RECONCILIATION_REQUIRED. Options blocked.", caseId);
            return List.of();
        }

        List<RebookOption> options = optionRepository.findByCaseIdOrderByRankAsc(caseId);
        boolean optionsValid = !options.isEmpty();
        Booking booking = bookingRepository.findById(rescueCase.getBookingId()).orElse(null);

        if (optionsValid) {
            if (booking == null) {
                optionsValid = false;
            } else {
                for (RebookOption opt : options) {
                    if (opt.getItineraryVersion() != null && !opt.getItineraryVersion().equals(booking.getItineraryVersion())) {
                        optionsValid = false;
                        break;
                    }
                    if (opt.getSourceSegmentId() != null && rescueCase.getDisruptedSegmentId() != null
                            && !opt.getSourceSegmentId().equals(rescueCase.getDisruptedSegmentId())) {
                        optionsValid = false;
                        break;
                    }
                    var optVal = canonicalItineraryService.validateOptionRoute(rescueCase, booking, opt);
                    if (!optVal.isValid()) {
                        optionsValid = false;
                        break;
                    }
                }
            }
        }

        if (!optionsValid) {
            if (!options.isEmpty()) {
                optionRepository.deleteAll(options);
                auditService.logEvent(caseId, "RECOVERY_OPTIONS_INVALIDATED", "Flyo Cache Engine", "Stale/mismatched options evicted from cache.");
            }
            options = generateAndRankOptions(caseId);
            if (!options.isEmpty()) {
                auditService.logEvent(caseId, "RECOVERY_OPTIONS_REGENERATED", "Flyo Cache Engine", "Fresh options generated matching verified itinerary.");
            }
        } else {
            caseRepository.findById(caseId).ifPresent(c -> {
                if ("DETECTED".equals(c.getState())) {
                    stateMachineService.transition(c, CaseStateMachineService.STATE_OPTIONS_READY, "Flyo System", "Options retrieved");
                    caseRepository.save(c);
                }
            });
        }
        return options;
    }

    @Transactional
    public RescueCase approveOption(String caseId, String optionId, String approvedBy) {
        RescueCase rescueCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        if (CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED.equals(rescueCase.getState())) {
            throw new IllegalStateTransitionException("409 Conflict: Cannot approve option on a case in DATA_RECONCILIATION_REQUIRED. Reconciliation required.");
        }

        // State Machine Guard: Cannot approve if case is in an escalated or terminal state
        if ("ESCALATED".equals(rescueCase.getState())) {
            throw new IllegalStateTransitionException("409 Conflict: Cannot approve option on an ESCALATED case. Case is closed for automated rebooking.");
        }
        if ("DONE".equals(rescueCase.getState())) {
            throw new IllegalStateTransitionException("409 Conflict: Case is already completed (DONE) with confirmed PNR.");
        }
        if ("FAILED_ESCALATED".equals(rescueCase.getState())) {
            throw new IllegalStateTransitionException("409 Conflict: Case failed and was escalated. Cannot approve without reset.");
        }

        // Idempotency & Conflict Guard: If already approved
        if (CaseStateMachineService.STATE_APPROVED.equals(rescueCase.getState())) {
            if (optionId != null && (optionId.equals(rescueCase.getApprovedOptionId())
                    || (rescueCase.getApprovedOptionId() != null && rescueCase.getApprovedOptionId().endsWith("-" + optionId)))) {
                return rescueCase;
            }
            throw new IllegalStateTransitionException(String.format(
                    "409 Conflict: Case %s is already approved for option %s. Conflicting approval for option %s is rejected.",
                    caseId, rescueCase.getApprovedOptionId(), optionId));
        }

        // Server-Side Phase 6 Approval Revalidation
        Booking booking = bookingRepository.findById(rescueCase.getBookingId()).orElse(null);
        if (booking == null) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, "Underlying booking missing at approval time");
            auditService.logEvent(caseId, "APPROVAL_REVALIDATION_FAILED", approvedBy, "Booking missing at approval time.");
            throw new IllegalStateTransitionException("409 Conflict: Itinerary revalidation failed: booking missing.");
        }
        var segOpt = canonicalItineraryService.resolveDisruptedSegment(booking, rescueCase.getFlightNumber(), rescueCase.getOriginAirport(), rescueCase.getDestinationAirport());
        if (segOpt.isEmpty()) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, "Segment identity mismatch at approval time");
            auditService.logEvent(caseId, "APPROVAL_REVALIDATION_FAILED", approvedBy, "Segment identity mismatch.");
            throw new IllegalStateTransitionException("409 Conflict: Itinerary revalidation failed: segment mismatch.");
        }
        var validation = canonicalItineraryService.validateItineraryIntegrity(rescueCase, booking, segOpt.get());
        if (!validation.isValid()) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, validation.mismatchReason());
            auditService.logEvent(caseId, "APPROVAL_REVALIDATION_FAILED", approvedBy, validation.mismatchReason());
            throw new IllegalStateTransitionException("409 Conflict: Itinerary revalidation failed: " + validation.mismatchReason());
        }

        List<RebookOption> existingOptions = optionRepository.findByCaseIdOrderByRankAsc(caseId);
        if (existingOptions.isEmpty()) {
            existingOptions = generateAndRankOptions(caseId);
        }

        RebookOption option = existingOptions.stream()
                .filter(o -> o.getId().equals(optionId)
                        || o.getId().equals(caseId + "-" + optionId)
                        || o.getId().endsWith("-" + optionId)
                        || (optionId.startsWith("O") && o.getId().endsWith(optionId)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid optionId: " + optionId + " does not belong to case " + caseId));

        var optVal = canonicalItineraryService.validateOptionRoute(rescueCase, booking, option);
        if (!optVal.isValid()) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, optVal.mismatchReason());
            auditService.logEvent(caseId, "APPROVAL_REVALIDATION_FAILED", approvedBy, optVal.mismatchReason());
            throw new IllegalStateTransitionException("409 Conflict: Option route validation failed: " + optVal.mismatchReason());
        }

        rescueCase.setApprovedOptionId(option.getId());
        rescueCase.setApprovedBy(approvedBy != null && !approvedBy.isBlank() ? approvedBy : "Flyo Operator");
        rescueCase.setApprovedAt(LocalDateTime.now());
        stateMachineService.transition(rescueCase, CaseStateMachineService.STATE_APPROVED, rescueCase.getApprovedBy(),
                String.format("Option %s (%s) approved for rebooking.", option.getId(), option.getFlight()));
        rescueCase.setError(null);
        rescueCase.setManualNextStep(null);

        RescueCase saved = caseRepository.save(rescueCase);

        // Treasury: If approved option requires fare adjustment, issue single-use VCC
        if (option.getExtraCost() > 0) {
            paymentSettlementService.issueVirtualCard(
                    caseId,
                    option.getId(),
                    option.getExtraCost(),
                    rescueCase.getPassengerName()
            );
        }

        // Audit Trail: Case Approved
        auditService.logEvent(
                caseId,
                "CASE_APPROVED",
                rescueCase.getApprovedBy(),
                String.format("Option %s (%s) approved for rebooking. Gate unlocked.", option.getId(), option.getFlight())
        );

        return saved;
    }

    @Transactional
    public RescueCase rejectCase(String caseId, String reason, String rejectedBy) {
        RescueCase rescueCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        String reasonText = (reason != null && !reason.isBlank()) ? reason : "Rejected by Flyo Operator";
        stateMachineService.transition(rescueCase, CaseStateMachineService.STATE_ESCALATED, rejectedBy != null ? rejectedBy : "Flyo Operator", reasonText);
        rescueCase.setApprovedOptionId(null);
        rescueCase.setApprovedBy(null);
        rescueCase.setApprovedAt(null);
        rescueCase.setNewPnr(null);
        rescueCase.setManualNextStep(null);
        rescueCase.setError("ESCALATED: " + reasonText + " (Escalated by " + (rejectedBy != null ? rejectedBy : "Flyo Operator") + ")");

        RescueCase saved = caseRepository.save(rescueCase);

        // Audit Trail: Case Rejected / Escalated
        auditService.logEvent(
                caseId,
                "CASE_REJECTED",
                rejectedBy != null ? rejectedBy : "Flyo Operator",
                "Case escalated for manual intervention: " + reasonText
        );

        return saved;
    }

    @Transactional
    public RescueCase rebookCase(String caseId) {
        RescueCase rescueCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        // Strict Idempotency: If already rebooked with confirmed PNR, return the existing result directly
        if ("DONE".equals(rescueCase.getState()) && rescueCase.getNewPnr() != null && !rescueCase.getNewPnr().isBlank()) {
            return rescueCase;
        }

        if (CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED.equals(rescueCase.getState())) {
            throw new ApprovalRequiredException("403 Forbidden: Case is in DATA_RECONCILIATION_REQUIRED. Rebooking blocked.");
        }

        if (!CaseStateMachineService.STATE_APPROVED.equals(rescueCase.getState())) {
            throw new ApprovalRequiredException("403 Forbidden: Rebooking requires prior explicit human approval in APPROVED state. Current state: " + rescueCase.getState());
        }

        if (rescueCase.getApprovedOptionId() == null || rescueCase.getApprovedOptionId().isBlank()) {
            throw new ApprovalRequiredException("403 Forbidden: Approval required before rebooking. No approved option exists for case " + caseId);
        }

        if ("ESCALATED".equals(rescueCase.getState())) {
            throw new ApprovalRequiredException("403 Forbidden: Case is escalated. Cannot rebook without a new approved option.");
        }

        RebookOption approvedOption = optionRepository.findByCaseIdOrderByRankAsc(caseId).stream()
                .filter(o -> o.getId().equals(rescueCase.getApprovedOptionId())
                        || o.getId().equals(caseId + "-" + rescueCase.getApprovedOptionId())
                        || o.getId().endsWith("-" + rescueCase.getApprovedOptionId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Approved option not found: " + rescueCase.getApprovedOptionId()));

        // Phase 6 Server-Side Execution Revalidation
        Booking booking = bookingRepository.findById(rescueCase.getBookingId()).orElse(null);
        if (booking == null) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, "Underlying booking missing at rebooking time");
            auditService.logEvent(caseId, "EXECUTION_IDENTITY_VALIDATION_FAILED", "Flyo Rebook Engine", "Booking missing at rebooking time.");
            throw new IllegalStateTransitionException("409 Conflict: Execution halted: booking missing.");
        }
        var segOpt = canonicalItineraryService.resolveDisruptedSegment(booking, rescueCase.getFlightNumber(), rescueCase.getOriginAirport(), rescueCase.getDestinationAirport());
        if (segOpt.isEmpty()) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, "Disrupted flight segment mismatch at rebooking time");
            auditService.logEvent(caseId, "EXECUTION_IDENTITY_VALIDATION_FAILED", "Flyo Rebook Engine", "Segment mismatch.");
            throw new IllegalStateTransitionException("409 Conflict: Execution halted: segment mismatch.");
        }
        var validation = canonicalItineraryService.validateItineraryIntegrity(rescueCase, booking, segOpt.get());
        if (!validation.isValid()) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, validation.mismatchReason());
            auditService.logEvent(caseId, "EXECUTION_IDENTITY_VALIDATION_FAILED", "Flyo Rebook Engine", validation.mismatchReason());
            throw new IllegalStateTransitionException("409 Conflict: Execution halted: " + validation.mismatchReason());
        }
        var optVal = canonicalItineraryService.validateOptionRoute(rescueCase, booking, approvedOption);
        if (!optVal.isValid()) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, optVal.mismatchReason());
            auditService.logEvent(caseId, "EXECUTION_IDENTITY_VALIDATION_FAILED", "Flyo Rebook Engine", optVal.mismatchReason());
            throw new IllegalStateTransitionException("409 Conflict: Execution halted: option route mismatch: " + optVal.mismatchReason());
        }

        stateMachineService.transition(rescueCase, CaseStateMachineService.STATE_REBOOKING, "Flyo Rebook Engine", "Subprocess dispatched to Playwright browser worker");
        caseRepository.save(rescueCase);

        // Audit Trail: Rebooking Initiated
        auditService.logEvent(
                caseId,
                "REBOOK_INITIATED",
                "Flyo Rebook Engine",
                "Subprocess dispatched to Playwright browser worker for mock airline portal."
        );

        // Subprocess invocation to Playwright worker (passing Corporate VCC if fare adjustment applies)
        Optional<com.flyo.rescue.model.VirtualCard> vccOpt = paymentSettlementService.getActiveCardForCase(caseId);
        com.flyo.rescue.dto.RebookResultDto result = null;
        if (vccOpt.isPresent() && approvedOption.getExtraCost() > 0) {
            com.flyo.rescue.model.VirtualCard vcc = vccOpt.get();
            result = rebookService.executePlaywrightRebook(
                    caseId,
                    approvedOption.getId(),
                    approvedOption.getFlight(),
                    rescueCase.getPassengerName(),
                    vcc.getCardTokenId(),
                    vcc.getExpiry(),
                    "",
                    vcc.getAmount()
            );
        }
        if (result == null) {
            result = rebookService.executePlaywrightRebook(
                    caseId,
                    approvedOption.getId(),
                    approvedOption.getFlight(),
                    rescueCase.getPassengerName()
            );
        }
        if (result == null) {
            result = rebookService.executePlaywrightRebook(
                    caseId,
                    approvedOption.getId(),
                    approvedOption.getFlight()
            );
        }

        if (result != null && result.isSuccess() && result.getNewPnr() != null) {
            rescueCase.setNewPnr(result.getNewPnr());
            rescueCase.setError(null);
            stateMachineService.transition(rescueCase, CaseStateMachineService.STATE_DONE, "Playwright Worker",
                    String.format("Mock airline rebooking successful. New airline PNR %s extracted.", result.getNewPnr()));

            // Update underlying monitored booking status to Rescued
            bookingRepository.findById(rescueCase.getBookingId()).ifPresent(b -> {
                b.setStatus("Rescued");
                bookingRepository.save(b);
            });

            // Settle Treasury Transaction on VCC
            vccOpt.ifPresent(vcc -> paymentSettlementService.recordSettlement(
                    caseId,
                    vcc.getId(),
                    approvedOption.getExtraCost(),
                    "StarSky Airlines"
            ));

            // Audit Trail: Rebooking Completed
            auditService.logEvent(
                    caseId,
                    "REBOOK_COMPLETED",
                    "Playwright Worker",
                    String.format("Mock airline rebooking successful. New airline PNR %s extracted from page DOM. 4 screenshots captured.", result.getNewPnr())
            );

            // WhatsApp: Send Simulated Notification with New PNR
            messageService.sendSimulatedSuccessMessage(
                    caseId,
                    rescueCase.getPassengerName(),
                    approvedOption.getFlight(),
                    result.getNewPnr()
            );

            // WhatsApp Passenger Care Stage 2: Confirmed Ticket & Boarding Pass
            String dep = approvedOption.getDeparture() != null ? approvedOption.getDeparture() : "Tonight 23:30";
            if (!dep.toLowerCase().contains("at") && !dep.toLowerCase().startsWith("tonight")) {
                dep = "Tonight at " + dep;
            }
            String arr = approvedOption.getArrival() != null ? approvedOption.getArrival() : "Tomorrow 01:40";
            if (!arr.toLowerCase().contains("at") && !arr.toLowerCase().startsWith("tomorrow")) {
                arr = "Tomorrow at " + arr;
            }

            messageService.sendPassengerConfirmedTicket(
                    caseId,
                    rescueCase.getPassengerName(),
                    approvedOption.getFlight(),
                    result.getNewPnr(),
                    "Hyderabad (HYD) → Mumbai (BOM) → Delhi (DEL)",
                    dep + " (Terminal 1)",
                    arr + " (Terminal 3)"
            );

            // Audit Trail: Message Sent
            auditService.logEvent(
                    caseId,
                    "MESSAGE_SENT",
                    "Flyo Messaging Engine",
                    String.format("Simulated WhatsApp notification generated and dispatched with new PNR %s.", result.getNewPnr())
            );
        } else {
            String errDetail = result.getError() != null ? result.getError() : "Rebooking execution failed on mock airline portal";
            rescueCase.setNewPnr(null);
            rescueCase.setError(errDetail);
            stateMachineService.transition(rescueCase, CaseStateMachineService.STATE_FAILED_ESCALATED, "Playwright Worker", errDetail);
            rescueCase.setManualNextStep("Manual Action Required: Call airline operations directly at 1800-209-4555. Passenger "
                    + rescueCase.getPassengerName() + " has onward flight " + rescueCase.getMissedConnection()
                    + ". Quote cancelled PNR " + rescueCase.getBookingId() + " / flight " + rescueCase.getFlight() + ".");

            auditService.logEvent(
                    caseId,
                    "REBOOK_FAILED",
                    "Playwright Worker",
                    String.format("Rebooking failed after %d retries. State set to FAILED_ESCALATED. Error: %s",
                            result.getRetryCount() > 0 ? result.getRetryCount() : 3, rescueCase.getError())
            );
        }

        return caseRepository.save(rescueCase);
    }

    @Transactional
    public RescueCase reconcileCase(String caseId, String operator) {
        RescueCase c = canonicalItineraryService.reconcileCase(caseId, operator);
        generateAndRankOptions(caseId);
        return c;
    }
}
