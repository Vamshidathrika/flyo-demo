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

        String optionId = rescueCase.getSelectedOptionId();
        if (optionId == null || optionId.isBlank()) {
            throw new IllegalStateException("No option currently selected for case " + caseId);
        }

        RebookOption option = optionRepository.findById(optionId)
                .orElseThrow(() -> new IllegalArgumentException("Option not found: " + optionId));

        return passengerOtpService.createPassengerOtpChallenge(
                caseId, passengerPhone, option.getId(), option.getExtraCost(), "INR"
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
            rescueCase.setApprovedBy("Passenger (OTP Verified: " + passengerPhone + ")");
            rescueCase.setApprovedAt(LocalDateTime.now());
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
                rescueCaseRepository.save(rescueCase);
            } else if ("LOCKED_OUT".equalsIgnoreCase(result.reason())) {
                if (!CaseStateMachineService.STATE_ESCALATED.equals(rescueCase.getState())) {
                    stateMachineService.transition(
                            rescueCase,
                            CaseStateMachineService.STATE_ESCALATED,
                            "Flyo Security Engine",
                            "Case escalated to operations desk due to OTP security lockout"
                    );
                    rescueCaseRepository.save(rescueCase);
                }
            } else if ("INVALID_CODE".equalsIgnoreCase(result.reason())) {
                if (!CaseStateMachineService.STATE_OTP_FAILED.equals(rescueCase.getState())
                        && !CaseStateMachineService.STATE_ESCALATED.equals(rescueCase.getState())) {
                    stateMachineService.transition(
                            rescueCase,
                            CaseStateMachineService.STATE_OTP_FAILED,
                            "Flyo Security Engine",
                            "Invalid passenger OTP attempt recorded (" + result.reason() + ")"
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
                rescueCaseRepository.save(rescueCase);
            }
        }

        return result;
    }
}
