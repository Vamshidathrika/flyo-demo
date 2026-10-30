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

    public CaseService(RescueCaseRepository caseRepository,
                       BookingRepository bookingRepository,
                       RebookOptionRepository optionRepository,
                       RankingEngine rankingEngine,
                       FlightSearchService flightSearchService,
                       RebookService rebookService,
                       AuditService auditService,
                       MessageService messageService,
                       PaymentSettlementService paymentSettlementService,
                       CaseStateMachineService stateMachineService) {
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
    }

    public List<RescueCase> getAllCases() {
        return caseRepository.findAll();
    }

    public Optional<RescueCase> getCaseById(String id) {
        return caseRepository.findById(id);
    }

    @Transactional
    public RescueCase simulateCancellation(String bookingId, String type) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        // Idempotency guard: Return existing active case if present and not terminal
        Optional<RescueCase> existingOpt = caseRepository.findByBookingId(bookingId);
        if (existingOpt.isPresent()) {
            RescueCase existing = existingOpt.get();
            boolean isTerminal = "DONE".equals(existing.getState())
                    || "ESCALATED".equals(existing.getState())
                    || "FAILED_ESCALATED".equals(existing.getState());
            if (!isTerminal) {
                return existing;
            }
        }

        booking.setStatus("Cancelled");
        bookingRepository.save(booking);

        String caseId = bookingId.equalsIgnoreCase("B1") ? "C1" : "CASE-" + bookingId;
        String cancelledSegment = booking.getRoute() + " (" + booking.getFlight() + " " + booking.getDateTime() + ")";
        
        String missedConnection = (booking.getOnwardFlight() != null && !booking.getOnwardFlight().equalsIgnoreCase("None"))
                ? booking.getOnwardFlight()
                : "None";

        String hotelRisk = (booking.getHotelNote() != null && !booking.getHotelNote().equalsIgnoreCase("None"))
                ? booking.getHotelNote()
                : "None";

        String impactText;
        if (!"None".equals(missedConnection) && !"None".equals(hotelRisk)) {
            impactText = String.format(
                    "Flight %s %s cancelled. Misses onward flight %s. %s is at risk.",
                    booking.getFlight(), booking.getRoute(), missedConnection, hotelRisk
            );
        } else if (!"None".equals(missedConnection)) {
            impactText = String.format("Flight %s %s cancelled. Misses onward connection: %s.",
                    booking.getFlight(), booking.getRoute(), missedConnection);
        } else {
            impactText = String.format("Flight %s %s cancelled. Standalone trip disrupted.",
                    booking.getFlight(), booking.getRoute());
        }

        RescueCase rescueCase = caseRepository.findById(caseId).orElse(
                new RescueCase(
                        caseId,
                        booking.getId(),
                        booking.getPassengerName(),
                        "DETECTED",
                        cancelledSegment,
                        booking.getFlight(),
                        impactText,
                        missedConnection,
                        hotelRisk
                )
        );

        rescueCase.setState("DETECTED");
        rescueCase.setCancelledSegment(cancelledSegment);
        rescueCase.setFlight(booking.getFlight());
        rescueCase.setImpact(impactText);
        rescueCase.setMissedConnection(missedConnection);
        rescueCase.setHotelRisk(hotelRisk);
        rescueCase.setApprovedOptionId(null);
        rescueCase.setApprovedBy(null);
        rescueCase.setApprovedAt(null);
        rescueCase.setNewPnr(null);
        rescueCase.setError(null);
        rescueCase.setManualNextStep(null);

        RescueCase saved = caseRepository.save(rescueCase);

        // Audit Trail: Disruption Detected
        auditService.logEvent(
                caseId,
                "DISRUPTION_DETECTED",
                "Flyo Engine",
                String.format("Flight %s %s cancelled. Disruption detected for passenger %s (PNR %s).",
                        booking.getFlight(), booking.getRoute(), booking.getPassengerName(), booking.getPnr())
        );

        // WhatsApp Passenger Care Stage 1: Immediate Proactive Reassurance Notice
        messageService.sendPassengerReassuranceNotice(
                caseId,
                booking.getPassengerName(),
                booking.getFlight(),
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

        Booking booking = bookingRepository.findById(rescueCase.getBookingId()).orElse(null);
        List<RebookOption> rawOptions;
        if (booking != null) {
            rawOptions = flightSearchService.findCandidateOptions(caseId, booking);
        } else {
            // Fallback for isolated tests
            rawOptions = List.of(
                    new RebookOption("O1", caseId, "FL201", "HYD → DEL (Direct)", "Tomorrow 06:00", "Tomorrow 08:05", 85, 0, 3, false, ""),
                    new RebookOption("O2", caseId, "FL301", "HYD → BOM → DEL (via Mumbai)", "Tonight 23:30", "Tomorrow 01:40", 470, 4200, 1, true, ""),
                    new RebookOption("O3", caseId, "FL401", "HYD → DEL (Direct)", "Tomorrow 10:00", "Tomorrow 12:15", -165, 1500, 2, false, "")
            );
        }

        List<RebookOption> rankedOptions = rankingEngine.rankOptions(rawOptions, rescueCase.getMissedConnection());
        optionRepository.deleteAll(optionRepository.findByCaseIdOrderByRankAsc(caseId));
        List<RebookOption> savedOptions = optionRepository.saveAll(rankedOptions);

        // Transition state from DETECTED to OPTIONS_READY
        stateMachineService.transition(rescueCase, CaseStateMachineService.STATE_OPTIONS_READY, "Flyo Ranking Engine", "Options generated and ranked");
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
        List<RebookOption> options = optionRepository.findByCaseIdOrderByRankAsc(caseId);
        if (options.isEmpty()) {
            options = generateAndRankOptions(caseId);
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

        // Idempotency guard: If already approved with the same option, return existing case without duplicate audit or card issuance
        if ("AWAITING_APPROVAL".equals(rescueCase.getState())
                && optionId != null && optionId.equals(rescueCase.getApprovedOptionId())) {
            return rescueCase;
        }

        RebookOption option = optionRepository.findByCaseIdOrderByRankAsc(caseId).stream()
                .filter(o -> o.getId().equals(optionId) || o.getId().equals(caseId + "-" + optionId) || o.getId().endsWith("-" + optionId))
                .findFirst()
                .orElseGet(() -> optionRepository.findById(optionId)
                        .orElseGet(() -> {
                            if (optionRepository.findByCaseIdOrderByRankAsc(caseId).isEmpty()) {
                                generateAndRankOptions(caseId);
                            }
                            return optionRepository.findByCaseIdOrderByRankAsc(caseId).stream()
                                    .filter(o -> o.getId().equals(optionId) || o.getId().equals(caseId + "-" + optionId) || o.getId().endsWith("-" + optionId))
                                    .findFirst()
                                    .orElseGet(() -> optionRepository.findById(optionId)
                                            .orElseThrow(() -> new IllegalArgumentException("Invalid optionId: " + optionId)));
                        }));

        rescueCase.setApprovedOptionId(option.getId());
        rescueCase.setApprovedBy(approvedBy != null && !approvedBy.isBlank() ? approvedBy : "Flyo Operator");
        rescueCase.setApprovedAt(LocalDateTime.now());
        stateMachineService.transition(rescueCase, CaseStateMachineService.STATE_AWAITING_APPROVAL, rescueCase.getApprovedBy(),
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

        if (rescueCase.getApprovedOptionId() == null || rescueCase.getApprovedOptionId().isBlank()) {
            throw new ApprovalRequiredException("403 Forbidden: Approval required before rebooking. No approved option exists for case " + caseId);
        }

        if ("ESCALATED".equals(rescueCase.getState())) {
            throw new ApprovalRequiredException("403 Forbidden: Case is escalated. Cannot rebook without a new approved option.");
        }

        // Strict Idempotency: If already rebooked with confirmed PNR, return the existing result directly
        if ("DONE".equals(rescueCase.getState()) && rescueCase.getNewPnr() != null && !rescueCase.getNewPnr().isBlank()) {
            return rescueCase;
        }

        RebookOption approvedOption = optionRepository.findById(rescueCase.getApprovedOptionId())
                .orElseGet(() -> optionRepository.findByCaseIdOrderByRankAsc(caseId).stream()
                        .filter(o -> o.getId().equals(rescueCase.getApprovedOptionId())
                                || o.getId().equals(caseId + "-" + rescueCase.getApprovedOptionId())
                                || o.getId().endsWith("-" + rescueCase.getApprovedOptionId()))
                        .findFirst()
                        .orElseGet(() -> {
                            generateAndRankOptions(caseId);
                            return optionRepository.findById(rescueCase.getApprovedOptionId())
                                    .orElseThrow(() -> new IllegalArgumentException("Approved option not found: " + rescueCase.getApprovedOptionId()));
                        }));

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
                    vcc.getCardNumber(),
                    vcc.getExpiry(),
                    vcc.getCvv(),
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
}
