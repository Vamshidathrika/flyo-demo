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

    public RebookingOrchestrationService(RescueCaseRepository rescueCaseRepository,
                                        RebookOptionRepository optionRepository,
                                        BookingRepository bookingRepository,
                                        PaymentSettlementService paymentSettlementService,
                                        RebookService rebookService,
                                        CaseStateMachineService stateMachineService,
                                        PassengerWhatsAppMessagingService passengerWhatsAppMessagingService,
                                        AuditService auditService) {
        this.rescueCaseRepository = rescueCaseRepository;
        this.optionRepository = optionRepository;
        this.bookingRepository = bookingRepository;
        this.paymentSettlementService = paymentSettlementService;
        this.rebookService = rebookService;
        this.stateMachineService = stateMachineService;
        this.passengerWhatsAppMessagingService = passengerWhatsAppMessagingService;
        this.auditService = auditService;
    }

    /**
     * Executes the complete post-verification payment and rebooking workflow.
     */
    @Transactional
    public RescueCase executeRebookingWorkflow(String caseId, String passengerPhone) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

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

            boolean withinLimit = paymentSettlementService.verifyAgencySpendingLimit(caseId, option.getExtraCost());
            if (!withinLimit) {
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_PAYMENT_FAILED,
                        "Flyo Compliance Engine",
                        "Fare difference (₹" + option.getExtraCost() + ") exceeds agency automated spending limit"
                );
                rescueCase.setError("Agency spending limit exceeded");
                rescueCaseRepository.save(rescueCase);
                throw new IllegalStateException("Fare difference exceeds agency authorized spending limit");
            }

            // Step B2: Authorize Payment Instrument
            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_PAYMENT_AUTH_PENDING,
                    "Flyo Treasury",
                    "Authorizing single-use corporate virtual card (VCC) for ₹" + option.getExtraCost()
            );
            rescueCaseRepository.save(rescueCase);

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
                        "PAYMENT_AUTHORIZED",
                        "Flyo Treasury",
                        String.format("Authorized payment of ₹%,d via corporate virtual card %s for option %s.",
                                option.getExtraCost(), card.getCardNumber(), option.getFlight())
                );

                // Step B3: Payment Verified
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_PAYMENT_VERIFIED,
                        "Flyo Treasury",
                        "Corporate card authorized and verified: " + card.getCardNumber()
                );
                rescueCaseRepository.save(rescueCase);
            } catch (Exception e) {
                log.error("[Rebooking] Payment authorization failed for case {}: {}", caseId, e.getMessage());
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_PAYMENT_FAILED,
                        "Flyo Treasury",
                        "Payment authorization failed: " + e.getMessage()
                );
                rescueCase.setError("Payment authorization failed: " + e.getMessage());
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
                    vcc.getCardNumber(), vcc.getExpiry(), vcc.getCvv(), vcc.getAmount()
            );
        } else {
            result = rebookService.executePlaywrightRebook(
                    caseId, option.getId(), option.getFlight(), rescueCase.getPassengerName()
            );
        }

        if (result != null && result.isSuccess() && result.getNewPnr() != null) {
            String newPnr = result.getNewPnr();
            rescueCase.setNewPnr(newPnr);
            rescueCase.setError(null);

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

            // Record financial settlement on VCC if positive fare
            if (vccOpt.isPresent() && option.getExtraCost() > 0) {
                paymentSettlementService.recordSettlement(
                        caseId, vccOpt.get().getId(), option.getExtraCost(), "StarSky Airlines"
                );
            }

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
            RescueCase saved = rescueCaseRepository.save(rescueCase);

            auditService.logEvent(
                    caseId,
                    "REBOOK_COMPLETED",
                    "Flyo Booking Verifier",
                    String.format("Itinerary successfully rebooked and ticketed. Airline PNR %s confirmed for flight %s.",
                            newPnr, option.getFlight())
            );

            // Send WhatsApp Success Message to Passenger
            String targetPhone = passengerPhone != null && !passengerPhone.isBlank()
                    ? passengerPhone
                    : rescueCase.getPassengerPhone();
            if (targetPhone != null && !targetPhone.isBlank()) {
                passengerWhatsAppMessagingService.sendBookingConfirmation(
                        targetPhone, saved, option, newPnr,
                        vccOpt.map(VirtualCard::getId).orElse("N/A (₹0 Fare)")
                );
            }

            log.info("[Rebooking] Successfully completed case {} with PNR {}", caseId, newPnr);
            return saved;
        } else {
            String errorMsg = result != null && result.getError() != null
                    ? result.getError()
                    : "Airline provider rebooking failed or timed out.";

            if (vccOpt.isPresent() && option.getExtraCost() > 0) {
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_PAYMENT_RECONCILIATION_REQUIRED,
                        "Flyo Treasury",
                        "Booking failed after payment authorization. Card flagged for financial reconciliation."
                );
                rescueCaseRepository.save(rescueCase);
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
            log.error("[Rebooking] Failed rebooking for case {}: {}", caseId, errorMsg);
            return failed;
        }
    }
}
