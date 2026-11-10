package com.flyo.rescue.service;

import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Passenger Confirmation and Verification Service.
 *
 * Enforces mandatory passenger OTP verification for both positive fare and zero-fare recovery options.
 */
@Service
public class PassengerConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(PassengerConfirmationService.class);

    private final RescueCaseRepository rescueCaseRepository;
    private final RebookOptionRepository optionRepository;
    private final PassengerOtpService passengerOtpService;
    private final CaseStateMachineService stateMachineService;
    private final AuditService auditService;

    public PassengerConfirmationService(RescueCaseRepository rescueCaseRepository,
                                       RebookOptionRepository optionRepository,
                                       PassengerOtpService passengerOtpService,
                                       CaseStateMachineService stateMachineService,
                                       AuditService auditService) {
        this.rescueCaseRepository = rescueCaseRepository;
        this.optionRepository = optionRepository;
        this.passengerOtpService = passengerOtpService;
        this.stateMachineService = stateMachineService;
        this.auditService = auditService;
    }

    /**
     * Issues the confirmation OTP for the currently selected option.
     */
    @Transactional
    public String requestConfirmationOtp(String caseId, String passengerPhone) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        if (stateMachineService.isTerminal(rescueCase.getState())
                || CaseStateMachineService.STATE_PASSENGER_DECLINED.equals(rescueCase.getState())
                || CaseStateMachineService.STATE_ABANDONED.equals(rescueCase.getState())
                || CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED.equals(rescueCase.getState())) {
            throw new IllegalStateException("Cannot request confirmation OTP: case " + caseId + " is in state " + rescueCase.getState());
        }

        String optionId = rescueCase.getSelectedOptionId();
        if (optionId == null || optionId.isBlank()) {
            throw new IllegalStateException("No option currently selected for case " + caseId);
        }

        RebookOption option = optionRepository.findById(optionId)
                .orElseThrow(() -> new IllegalArgumentException("Option not found: " + optionId));

        return passengerOtpService.createPassengerOtpChallenge(
                caseId, passengerPhone, option.getId(), option.getExtraCost(), "INR",
                rescueCase.getRescueAttemptId(), rescueCase.getTenantId(), "CONFIRM_RESCUE"
        );
    }

    /**
     * Verifies the passenger's submitted OTP code.
     * Transitions case to PASSENGER_VERIFIED on success.
     */
    @Transactional
    public PassengerOtpService.PassengerOtpResult confirmAndVerify(String caseId, String passengerPhone, String submittedCode) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        // Security check: If case is abandoned, declined, or already completed, reject immediately
        String state = rescueCase.getState();
        if (stateMachineService.isTerminal(state)
                || CaseStateMachineService.STATE_PASSENGER_DECLINED.equals(state)
                || CaseStateMachineService.STATE_ABANDONED.equals(state)
                || "CANCELLED".equalsIgnoreCase(state)
                || CaseStateMachineService.STATE_DONE.equals(state)
                || CaseStateMachineService.STATE_FAILED_ESCALATED.equals(state)
                || CaseStateMachineService.STATE_BOOKING_FAILED.equals(state)
                || CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED.equals(state)) {
            log.warn("[Passenger Confirmation] Rejected OTP verification for case {} because state is {}", caseId, state);
            return new PassengerOtpService.PassengerOtpResult(false, "ATTEMPT_ABANDONED", null);
        }

        String optionId = rescueCase.getSelectedOptionId();
        if (optionId == null || optionId.isBlank()) {
            return new PassengerOtpService.PassengerOtpResult(false, "NO_OPTION_SELECTED", null);
        }

        RebookOption option = optionRepository.findById(optionId)
                .orElseThrow(() -> new IllegalArgumentException("Option not found: " + optionId));

        PassengerOtpService.PassengerOtpResult result = passengerOtpService.verifyPassengerOtp(
                caseId, passengerPhone, submittedCode, option.getId(), option.getExtraCost()
        );

        if (result.valid()) {
            // Set passenger confirmed properties
            rescueCase.setApprovedOptionId(option.getId());
            rescueCase.setSelectedFlight(option.getFlight());
            rescueCase.setApprovedBy("Passenger (OTP Verified: " + passengerPhone + ")");
            rescueCase.setApprovedAt(LocalDateTime.now());
            rescueCase.setPassengerConfirmedAmount(option.getExtraCost());
            rescueCase.setPassengerConfirmedCurrency("INR");
            rescueCase.setPassengerOtpVerifiedAt(LocalDateTime.now());
            rescueCase.setPassengerConfirmedAmount(option.getExtraCost());
            rescueCase.setPassengerConfirmedCurrency("INR");
            rescueCase.setPassengerOtpVerifiedAt(LocalDateTime.now());

            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_PASSENGER_VERIFIED,
                    "Passenger (" + passengerPhone + ")",
                    String.format("Passenger OTP verified for option %s (Amount: ₹%,d). Confirmation locked.",
                            option.getId(), option.getExtraCost())
            );

            rescueCaseRepository.save(rescueCase);

            auditService.logEvent(
                    caseId,
                    "PASSENGER_CONFIRMATION_COMPLETED",
                    "Passenger (" + passengerPhone + ")",
                    String.format("Final passenger consent verified via OTP. Itinerary locked for option %s. Authorized amount: ₹%,d.",
                            option.getId(), option.getExtraCost())
            );

            log.info("[Passenger Confirmation] Case {} successfully verified by passenger OTP", caseId);
        } else {
            if ("EXPIRED".equalsIgnoreCase(result.reason())) {
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_OTP_EXPIRED,
                        "Flyo Security Engine",
                        "Passenger OTP expired before entry"
                );
                // Closed-loop recovery: regenerate challenge and prompt retry
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_AWAITING_PASSENGER_OTP,
                        "Flyo Security Engine",
                        "Regenerated fresh OTP challenge for passenger retry"
                );
                rescueCaseRepository.save(rescueCase);
            } else if ("LOCKED_OUT".equalsIgnoreCase(result.reason())) {
                if (!CaseStateMachineService.STATE_ESCALATED.equals(rescueCase.getState())
                        && !CaseStateMachineService.STATE_LOCKED_OUT.equals(rescueCase.getState())) {
                    stateMachineService.transition(
                            rescueCase,
                            CaseStateMachineService.STATE_LOCKED_OUT,
                            "Flyo Security Engine",
                            "Passenger locked out after 5 consecutive invalid OTP attempts"
                    );
                    stateMachineService.transition(
                            rescueCase,
                            CaseStateMachineService.STATE_MANUAL_INTERVENTION_REQUIRED,
                            "Flyo Security Engine",
                            "Flagged for human travel desk verification"
                    );
                    stateMachineService.transition(
                            rescueCase,
                            CaseStateMachineService.STATE_ESCALATED,
                            "Flyo Security Engine",
                            "Case escalated to operations desk due to OTP security lockout"
                    );
                    rescueCaseRepository.save(rescueCase);
                }
            } else if ("INVALID_CODE".equalsIgnoreCase(result.reason())) {
                if (!CaseStateMachineService.STATE_ESCALATED.equals(rescueCase.getState())) {
                    stateMachineService.transition(
                            rescueCase,
                            CaseStateMachineService.STATE_OTP_FAILED,
                            "Flyo Security Engine",
                            "Invalid passenger OTP attempt recorded (" + result.reason() + ")"
                    );
                    // Closed-loop retry: remain in AWAITING_PASSENGER_OTP for next attempt
                    stateMachineService.transition(
                            rescueCase,
                            CaseStateMachineService.STATE_AWAITING_PASSENGER_OTP,
                            "Flyo Security Engine",
                            "Awaiting passenger retry submission"
                    );
                    rescueCaseRepository.save(rescueCase);
                }
            } else if ("FARE_CHANGED".equalsIgnoreCase(result.reason())) {
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_FARE_CHANGED,
                        "Flyo Security Engine",
                        "Fare difference changed. Requiring new passenger review and confirmation"
                );
                // Closed-loop recovery: revalidate option with new fare and request fresh consent
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_REVALIDATING_OPTION,
                        "Flyo Provider Bridge",
                        "Recalculating complete cost including taxes, exchange fees, and surcharges"
                );
                stateMachineService.transition(
                        rescueCase,
                        CaseStateMachineService.STATE_AWAITING_PASSENGER_CONFIRMATION,
                        "Flyo Confirmation Engine",
                        "Presenting revised itinerary quote to passenger for fresh OTP acceptance"
                );
                rescueCaseRepository.save(rescueCase);
            }
        }

        return result;
    }
}
