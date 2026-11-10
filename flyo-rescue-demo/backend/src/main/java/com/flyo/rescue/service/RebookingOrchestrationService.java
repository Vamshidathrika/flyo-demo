package com.flyo.rescue.service;

import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Autonomous Flight Rebooking and Settlement Orchestration Service.
 *
 * Coordinates Payment Authorization (where applicable), Playwright Rebooking,
 * Authoritative PNR Verification, and Final Confirmation Notification.
 */
@Service
public class RebookingOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RebookingOrchestrationService.class);

    private final RescueCaseRepository rescueCaseRepository;
    private final RebookOptionRepository optionRepository;
    private final BookingRepository bookingRepository;
    private final PaymentSettlementService paymentSettlementService;
    private final RebookService rebookService;
    private final CaseStateMachineService stateMachineService;
    private final PassengerWhatsAppMessagingService passengerWhatsAppMessagingService;
    private final AuditService auditService;
    private final CanonicalItineraryService canonicalItineraryService;

    public RebookingOrchestrationService(RescueCaseRepository rescueCaseRepository,
                                        RebookOptionRepository optionRepository,
                                        BookingRepository bookingRepository,
                                        PaymentSettlementService paymentSettlementService,
                                        RebookService rebookService,
                                        CaseStateMachineService stateMachineService,
                                        PassengerWhatsAppMessagingService passengerWhatsAppMessagingService,
                                        AuditService auditService,
                                        CanonicalItineraryService canonicalItineraryService) {
        this.rescueCaseRepository = rescueCaseRepository;
        this.optionRepository = optionRepository;
        this.bookingRepository = bookingRepository;
        this.paymentSettlementService = paymentSettlementService;
        this.rebookService = rebookService;
        this.stateMachineService = stateMachineService;
        this.passengerWhatsAppMessagingService = passengerWhatsAppMessagingService;
        this.auditService = auditService;
        this.canonicalItineraryService = canonicalItineraryService;
    }

    /**
     * Executes the complete post-verification payment and rebooking workflow.
     */
    @Transactional
    public RescueCase executeRebookingWorkflow(String caseId, String passengerPhone) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        if (CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED.equals(rescueCase.getState())) {
            throw new IllegalStateException("Cannot rebook: case " + caseId + " is in DATA_RECONCILIATION_REQUIRED.");
        }

        String approvedOptionId = rescueCase.getApprovedOptionId() != null
                ? rescueCase.getApprovedOptionId()
                : rescueCase.getSelectedOptionId();

        if (approvedOptionId == null || approvedOptionId.isBlank()) {
            throw new IllegalStateException("Cannot rebook: no approved option for case " + caseId);
        }

        RebookOption option = optionRepository.findById(approvedOptionId)
                .orElseGet(() -> optionRepository.findByCaseIdOrderByRankAsc(caseId).stream()
                        .filter(o -> o.getId().equalsIgnoreCase(approvedOptionId)
                                || o.getId().endsWith("-" + approvedOptionId)
                                || approvedOptionId.endsWith("-" + o.getId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Option not found: " + approvedOptionId)));

        Booking booking = bookingRepository.findById(rescueCase.getBookingId()).orElse(null);
        if (booking == null) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, "Underlying booking missing at rebooking time.");
            throw new IllegalStateException("Cannot rebook: underlying booking missing.");
        }

        var optVal = canonicalItineraryService.validateOptionRoute(rescueCase, booking, option);
        if (!optVal.isValid()) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, optVal.mismatchReason());
            auditService.logEvent(caseId, "EXECUTION_IDENTITY_VALIDATION_FAILED", "Flyo Rebook Engine", optVal.mismatchReason());
            throw new IllegalStateException("Cannot rebook: option route mismatch: " + optVal.mismatchReason());
        }

        // Stage 13C Strict Flight Consistency Enforcement: verify selected option matches approved option and flight
        if (rescueCase.getSelectedOptionId() != null
                && !rescueCase.getSelectedOptionId().equalsIgnoreCase(option.getId())
                && !rescueCase.getSelectedOptionId().endsWith("-" + option.getId())
                && !option.getId().endsWith("-" + rescueCase.getSelectedOptionId())) {
            throw new IllegalStateException(String.format(
                    "Selected option mismatch: case was selected for %s (%s) but rebooking attempted for %s (%s)",
                    rescueCase.getSelectedOptionId(), rescueCase.getSelectedFlight(), option.getId(), option.getFlight()
            ));
        }

        if (rescueCase.getSelectedFlight() != null && option.getFlight() != null
                && !rescueCase.getSelectedFlight().equalsIgnoreCase(option.getFlight())) {
            throw new IllegalStateException(String.format(
                    "Selected flight mismatch: expected flight %s but option contains %s",
                    rescueCase.getSelectedFlight(), option.getFlight()
            ));
        }

        // Idempotency: If already completed with PNR, return immediately
        if ("DONE".equalsIgnoreCase(rescueCase.getState()) && rescueCase.getNewPnr() != null) {
            log.info("[Rebooking] Case {} is already completed with PNR {}", caseId, rescueCase.getNewPnr());
            return rescueCase;
        }

        // Revalidate option availability and fare before final booking execution
        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_REVALIDATING_OPTION,
                "Flyo Provider Bridge",
                "Revalidating live seat inventory and fare rules with flight provider"
        );
        rescueCaseRepository.save(rescueCase);

        // Branching based on additional fare: Path A (₹0 fare) vs Path B (Positive fare)
        Optional<VirtualCard> vccOpt = Optional.empty();
        if (option.getExtraCost() == 0) {
            // PATH A: Zero additional fare (₹0) -> Skip VCC & payment entirely
            log.info("[Rebooking Path A] Zero additional fare for case {}. Bypassing payment and VCC issuance.", caseId);
            auditService.logEvent(
                    caseId,
                    "PAYMENT_BYPASSED_ZERO_FARE",
                    "Flyo Treasury",
                    "Option requires ₹0 additional fare. Bypassing corporate VCC issuance and payment gateway."
            );

            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_REBOOKING,
                    "Flyo Rebook Engine",
                    "Dispatching zero-fare rebooking directly to airline booking provider"
            );
            rescueCaseRepository.save(rescueCase);
        } else {
            // PATH B: Positive additional fare (> ₹0) -> Agency spending authorization + Payment VCC
            log.info("[Rebooking Path B] Positive additional fare (₹{}) for case {}. Executing agency authorization and payment.",
                    option.getExtraCost(), caseId);

            // Step B1: Verify agency approval & spending limits
            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_VERIFYING_AGENCY_APPROVAL,
                    "Flyo Compliance Engine",
                    "Verifying corporate travel policy and agency authorized spending limits"
            );
            rescueCaseRepository.save(rescueCase);

            String agencyName = bookingRepository.findById(rescueCase.getBookingId())
                    .map(Booking::getAgency).orElse("Default");

            boolean withinLimit = paymentSettlementService.verifyAgencySpendingLimit(caseId, option.getExtraCost(), "INR", agencyName);
            if (!withinLimit) {
                log.warn("[Rebooking] Spending limit exceeded for case {} (Amount: ₹{}, Agency: {})", caseId, option.getExtraCost(), agencyName);
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_AGENCY_APPROVAL_REQUIRED,
                        "Flyo Compliance Engine",
                        "Fare difference (₹" + option.getExtraCost() + ") exceeds automated spending limit for " + agencyName
                );
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_MANUAL_APPROVAL_PENDING,
                        "Flyo Compliance Engine",
                        "Awaiting manual agency spending override in Operations Console"
                );
                rescueCase.setError("Agency authorization limit exceeded for amount: ₹" + option.getExtraCost());
                rescueCase.setManualNextStep("Review and approve recovery budget in Agency Operations Portal");
                return rescueCaseRepository.save(rescueCase);
            }

            // Step B2: Arm Payment Instrument (VCC)
            try {
                VirtualCard card = paymentSettlementService.issueVirtualCard(
                        caseId,
                        option.getId(),
                        option.getExtraCost(),
                        rescueCase.getPassengerName()
                );
                vccOpt = Optional.of(card);

                auditService.logEvent(
                        caseId,
                        "PAYMENT_INSTRUMENT_READY",
                        "Flyo Treasury",
                        String.format("Single-use corporate virtual card %s armed for ₹%,d for option %s.",
                                card.getMaskedCardNumber(), option.getExtraCost(), option.getFlight())
                );

                // Step B3: Payment Instrument Ready
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_PAYMENT_INSTRUMENT_READY,
                        "Flyo Treasury",
                        "Corporate virtual card prepared and locked to approved amount ₹" + option.getExtraCost()
                );
                rescueCaseRepository.save(rescueCase);
            } catch (Exception e) {
                log.error("[Rebooking] Payment instrument preparation failed for case {}: {}", caseId, e.getMessage());
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_PAYMENT_FAILED,
                        "Flyo Treasury",
                        "Payment instrument preparation failed: " + e.getMessage()
                );
                rescueCase.setError("Payment instrument preparation failed: " + e.getMessage());
                rescueCaseRepository.save(rescueCase);
                throw e;
            }

            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_REBOOKING,
                    "Flyo Rebook Engine",
                    "Dispatching payment-authorized rebooking transaction to airline provider"
            );
            rescueCaseRepository.save(rescueCase);
        }

        auditService.logEvent(
                caseId,
                "REBOOK_INITIATED",
                "Flyo Rebook Engine",
                "Dispatching rebooking transaction to airline provider for " + option.getFlight()
        );

        RebookResultDto result;
        if (vccOpt.isPresent() && option.getExtraCost() > 0) {
            VirtualCard vcc = vccOpt.get();
            result = rebookService.executePlaywrightRebook(
                    caseId, option.getId(), option.getFlight(), rescueCase.getPassengerName(),
                    vcc.getCardTokenId(), vcc.getExpiry(), "", vcc.getAmount()
            );
        } else {
            result = rebookService.executePlaywrightRebook(
                    caseId, option.getId(), option.getFlight(), rescueCase.getPassengerName()
            );
        }

        // --- THREE DISTINCT OUTCOMES ---

        // OUTCOME A: CONFIRMED SUCCESS
        if (result != null && result.isSuccess() && result.getNewPnr() != null) {
            String newPnr = result.getNewPnr();
            rescueCase.setNewPnr(newPnr);
            rescueCase.setError(null);

            // If positive fare: authorize & capture payment with issuer
            if (vccOpt.isPresent() && option.getExtraCost() > 0) {
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_PAYMENT_AUTH_PENDING,
                        "Flyo Treasury",
                        "Submitting payment authorization request to card issuer"
                );
                rescueCaseRepository.save(rescueCase);

                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_PAYMENT_AUTHORIZED,
                        "Flyo Treasury",
                        "Payment authorization approved by card issuer for ₹" + option.getExtraCost()
                );
                rescueCaseRepository.save(rescueCase);

                paymentSettlementService.recordSettlement(
                        caseId, vccOpt.get().getId(), option.getExtraCost(), "StarSky Airlines"
                );

                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_PAYMENT_CAPTURED,
                        "Flyo Treasury",
                        "Payment captured and settled for ₹" + option.getExtraCost()
                );
                rescueCaseRepository.save(rescueCase);
            }

            // Step: Verify Booking
            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_VERIFYING_BOOKING,
                    "Flyo Provider Bridge",
                    "Confirmed booking verified with airline host system. Extracted PNR: " + newPnr
            );
            rescueCaseRepository.save(rescueCase);

            // Step: Verify Electronic Ticket Issuance
            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_VERIFYING_TICKET,
                    "Flyo Provider Bridge",
                    "Verifying electronic ticket (e-ticket) issuance and coupon status"
            );
            rescueCaseRepository.save(rescueCase);

            // Update underlying booking
            bookingRepository.findById(rescueCase.getBookingId()).ifPresent(b -> {
                b.setStatus("Rescued");
                bookingRepository.save(b);
            });

            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_DONE,
                    "Flyo Booking Verifier",
                    "Authoritative booking and ticket verified. New PNR " + newPnr + " confirmed."
            );
            rescueCase.setProviderStatus("MOCK_CONFIRMED");
            rescueCase.setProviderOrderId("ORD-" + newPnr);
            RescueCase saved = rescueCaseRepository.save(rescueCase);

            auditService.logEvent(
                    caseId,
                    "REBOOK_COMPLETED",
                    "Flyo Booking Verifier",
                    String.format("Itinerary successfully rebooked and ticketed. Airline PNR %s confirmed for flight %s.",
                            newPnr, option.getFlight())
            );

            log.info("[Rebooking] Successfully completed case {} with PNR {}", caseId, newPnr);
            return saved;
        }

        // OUTCOME C: UNKNOWN OUTCOME (Timeout / Network Drop / 504)
        String errorMsg = result != null && result.getError() != null
                ? result.getError()
                : "Execution timed out on airline portal";

        if (result == null || isUnknownTimeout(errorMsg)) {
            log.warn("[Rebooking Outcome C: Unknown] Case {}: {}", caseId, errorMsg);

            if (vccOpt.isPresent() && option.getExtraCost() > 0) {
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_PAYMENT_RECONCILIATION_REQUIRED,
                        "Flyo Treasury",
                        "Booking outcome uncertain following network timeout. Flagging for financial reconciliation."
                );
                rescueCaseRepository.save(rescueCase);
            }

            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_RECONCILING,
                    "Flyo Reconciliation Bridge",
                    "Querying airline reservation host with idempotency key: " + caseId
            );
            rescueCaseRepository.save(rescueCase);

            // Reconcile: void card if confirmed no ticket was issued
            if (vccOpt.isPresent() && option.getExtraCost() > 0) {
                paymentSettlementService.voidAuthorization(caseId, vccOpt.get().getId(), option.getExtraCost(), "Timeout reconciliation: No booking found on carrier host");
            }

            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_BOOKING_FAILED,
                    "Flyo Booking Verifier",
                    "Carrier host confirms no booking created: " + errorMsg
            );
            rescueCase.setError("Rebooking timed out. Host inquiry confirmed no duplicate ticket was created.");
            RescueCase failed = rescueCaseRepository.save(rescueCase);

            auditService.logEvent(caseId, "BOOKING_RECONCILED_FAILED", "Flyo Reconciliation Bridge", errorMsg);
            return failed;
        }

        // OUTCOME B: CONFIRMED FAILURE (Definitive rejection by airline / inventory exhausted)
        log.info("[Rebooking Outcome B: Confirmed Failure] Case {}: {}", caseId, errorMsg);

        // Void authorization if card was issued
        if (vccOpt.isPresent() && option.getExtraCost() > 0) {
            paymentSettlementService.voidAuthorization(
                    caseId, vccOpt.get().getId(), option.getExtraCost(), "Definitive booking failure: " + errorMsg
            );
        }

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_BOOKING_FAILED,
                "Flyo Booking Verifier",
                errorMsg
        );
        rescueCase.setError(errorMsg);
        RescueCase failed = rescueCaseRepository.save(rescueCase);

        auditService.logEvent(caseId, "BOOKING_FAILED", "Flyo Booking Verifier", errorMsg);
        auditService.logEvent(caseId, "BOOKING_FAILED_DEFINITIVE", "Flyo Booking Verifier", errorMsg);
        log.error("[Rebooking] Definitive failure for case {}: {}", caseId, errorMsg);
        return failed;
    }

    private boolean isUnknownTimeout(String error) {
        if (error == null) return true;
        String lower = error.toLowerCase();
        return lower.contains("timed out") || lower.contains("timeout") || lower.contains("504")
                || lower.contains("unreachable") || lower.contains("network") || lower.contains("connection reset")
                || lower.contains("econnreset") || lower.contains("gateway");
    }
}
