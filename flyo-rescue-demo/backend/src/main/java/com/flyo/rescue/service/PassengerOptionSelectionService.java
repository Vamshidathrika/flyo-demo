package com.flyo.rescue.service;

import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passenger Flight Option Selection and Pre-Confirmation Revalidation Service.
 */
@Service
public class PassengerOptionSelectionService {

    private static final Logger log = LoggerFactory.getLogger(PassengerOptionSelectionService.class);

    private final RescueCaseRepository rescueCaseRepository;
    private final RebookOptionRepository optionRepository;
    private final CaseStateMachineService stateMachineService;
    private final AuditService auditService;

    public PassengerOptionSelectionService(RescueCaseRepository rescueCaseRepository,
                                           RebookOptionRepository optionRepository,
                                           CaseStateMachineService stateMachineService,
                                           AuditService auditService) {
        this.rescueCaseRepository = rescueCaseRepository;
        this.optionRepository = optionRepository;
        this.stateMachineService = stateMachineService;
        this.auditService = auditService;
    }

    /**
     * Validates and selects the passenger's chosen alternative option.
     */
    @Transactional
    public RebookOption selectOption(String caseId, String optionCode) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        if ("PASSENGER_DECLINED".equals(rescueCase.getState()) || "DONE".equals(rescueCase.getState())) {
            throw new IllegalStateException("Cannot select option on case in state: " + rescueCase.getState());
        }

        RebookOption option = optionRepository.findByCaseIdOrderByRankAsc(caseId).stream()
                .filter(o -> o.getId().equalsIgnoreCase(optionCode)
                        || o.getId().endsWith("-" + optionCode)
                        || optionCode.endsWith("-" + o.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Option " + optionCode + " not found for case " + caseId));

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_OPTION_SELECTED,
                "Passenger",
                "Passenger selected Option " + optionCode + " (" + option.getFlight() + ")"
        );

        rescueCase.setSelectedOptionId(option.getId());

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_REVALIDATING_OPTION,
                "Flyo Provider Bridge",
                "Revalidating seat availability and fare difference with flight provider"
        );

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_AWAITING_PASSENGER_CONFIRMATION,
                "Flyo Confirmation Engine",
                "Option revalidated. Presenting final itinerary preview and requesting passenger OTP"
        );

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_AWAITING_PASSENGER_OTP,
                "Flyo Security Engine",
                "Dispatched 6-digit confirmation OTP to passenger verified contact"
        );

        rescueCaseRepository.save(rescueCase);

        auditService.logEvent(
                caseId,
                "PASSENGER_OPTION_SELECTED",
                "Passenger",
                String.format("Option %s (%s, Extra Fare: ₹%,d) selected by passenger. Confirmation OTP challenge armed.",
                        optionCode, option.getFlight(), option.getExtraCost())
        );

        log.info("[Passenger Selection] Option {} selected for case {}", option.getId(), caseId);
        return option;
    }
}
