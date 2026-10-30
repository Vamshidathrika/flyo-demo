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

        // Step 9: Payment Authorization (only when additional fare > 0)
        Optional<VirtualCard> vccOpt = Optional.empty();
        if (option.getExtraCost() > 0) {
            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_PAYMENT_AUTH_PENDING,
                    "Flyo Treasury",
                    "Authorizing payment instrument for approved fare difference of ₹" + option.getExtraCost()
            );
            rescueCaseRepository.save(rescueCase);

            try {
                vccOpt = Optional.of(paymentSettlementService.issueVirtualCard(
                        caseId,
                        option.getId(),
                        option.getExtraCost(),
                        rescueCase.getPassengerName()
                ));
                auditService.logEvent(
                        caseId,
                        "PAYMENT_AUTHORIZED",
                        "Flyo Treasury",
                        String.format("Authorized payment of ₹%,d via corporate virtual card %s for option %s.",
                                option.getExtraCost(), vccOpt.get().getCardNumber(), option.getFlight())
                );
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
        }

        // Step 10: Rebooking Execution
        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_REBOOKING,
                "Flyo Rebook Engine",
                "Dispatching rebooking subprocess to airline booking adapter"
        );
        rescueCaseRepository.save(rescueCase);

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_VERIFYING_BOOKING,
                "Flyo Provider Bridge",
                "Verifying confirmed reservation and extracting authoritative airline PNR"
        );
        rescueCaseRepository.save(rescueCase);

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
                    "Authoritative airline confirmation verified. New PNR " + newPnr + " confirmed."
            );
            RescueCase saved = rescueCaseRepository.save(rescueCase);

            auditService.logEvent(
                    caseId,
                    "REBOOK_COMPLETED",
                    "Flyo Booking Verifier",
                    String.format("Itinerary successfully rebooked. Airline PNR %s confirmed for flight %s.",
                            newPnr, option.getFlight())
            );

            // Step 11: Send WhatsApp Success Message to Passenger
            String targetPhone = passengerPhone != null && !passengerPhone.isBlank()
                    ? passengerPhone
                    : rescueCase.getPassengerPhone();
            if (targetPhone != null && !targetPhone.isBlank()) {
                passengerWhatsAppMessagingService.sendBookingConfirmation(
                        targetPhone, saved, option, newPnr,
                        vccOpt.map(VirtualCard::getId).orElse("N/A")
                );
            }

            log.info("[Rebooking] Successfully completed case {} with PNR {}", caseId, newPnr);
            return saved;
        } else {
            String errorMsg = result != null && result.getError() != null
                    ? result.getError()
                    : "Airline provider rebooking failed or timed out.";

            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_BOOKING_FAILED,
                    "Flyo Booking Verifier",
                    errorMsg
            );
            rescueCase.setError(errorMsg);
            rescueCaseRepository.save(rescueCase);

            auditService.logEvent(caseId, "BOOKING_FAILED", "Flyo Booking Verifier", errorMsg);
            log.error("[Rebooking] Failed rebooking for case {}: {}", caseId, errorMsg);
            throw new RuntimeException("Rebooking execution failed: " + errorMsg);
        }
    }
}
