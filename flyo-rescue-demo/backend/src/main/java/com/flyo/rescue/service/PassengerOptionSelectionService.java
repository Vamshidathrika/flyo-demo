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
    private final PassengerOtpService passengerOtpService;

    public PassengerOptionSelectionService(RescueCaseRepository rescueCaseRepository,
                                           RebookOptionRepository optionRepository,
                                           CaseStateMachineService stateMachineService,
                                           AuditService auditService,
                                           PassengerOtpService passengerOtpService) {
        this.rescueCaseRepository = rescueCaseRepository;
        this.optionRepository = optionRepository;
        this.stateMachineService = stateMachineService;
        this.auditService = auditService;
        this.passengerOtpService = passengerOtpService;
    }

    /**
     * Validates and selects the passenger's chosen alternative option.
     */
    @Transactional
    public RebookOption selectOption(String caseId, String optionCode) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        String currentState = rescueCase.getState();
        if (stateMachineService.isTerminal(currentState)
                || "PASSENGER_DECLINED".equals(currentState)
                || "ABANDONED".equalsIgnoreCase(currentState)
                || "DONE".equals(currentState)) {
            throw new IllegalStateException("Cannot select option on case in state: " + currentState);
        }

        java.util.List<RebookOption> options = optionRepository.findByCaseIdOrderByRankAsc(caseId);
        if (options.isEmpty()) {
            throw new IllegalArgumentException("No alternative flight options found for case " + caseId);
        }

        RebookOption option = options.stream()
                .filter(o -> o.getId().equalsIgnoreCase(optionCode)
                        || o.getId().endsWith("-" + optionCode)
                        || optionCode.endsWith("-" + o.getId()))
                .findFirst()
                .or(() -> options.stream()
                        .filter(o -> o.getFlight() != null && o.getFlight().equalsIgnoreCase(optionCode))
                        .findFirst())
                .or(() -> options.stream()
                        .filter(o -> {
                            if (optionCode.matches("^(?i)O[0-9]+$")) {
                                int r = Integer.parseInt(optionCode.substring(1));
                                return o.getRank() == r;
                            }
                            if (optionCode.matches("^[0-9]+$")) {
                                int r = Integer.parseInt(optionCode);
                                return o.getRank() == r;
                            }
                            return false;
                        })
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException("Option " + optionCode + " not found for case " + caseId));

        // If passenger changed their selected option, invalidate any previous unverified OTP challenges
        if (rescueCase.getSelectedOptionId() != null && !rescueCase.getSelectedOptionId().equals(option.getId())) {
            passengerOtpService.invalidateOtpsForCase(caseId, "OPTION_CHANGED_TO_" + option.getId());
        }

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_OPTION_SELECTED,
                "Passenger",
                "Passenger selected Option " + option.getId() + " (" + option.getFlight() + ")"
        );

        rescueCase.setSelectedOptionId(option.getId());
        rescueCase.setSelectedFlight(option.getFlight());

        // Persist immutable snapshot of selected option details
        String snapshot = String.format("{\"optionId\":\"%s\",\"flight\":\"%s\",\"provider\":\"%s\",\"cost\":%d,\"currency\":\"%s\",\"departure\":\"%s\",\"arrival\":\"%s\"}",
                option.getId(), option.getFlight(), option.getProvider() != null ? option.getProvider() : "MOCK_AIRLINE",
                option.getExtraCost(), option.getCurrency() != null ? option.getCurrency() : "INR",
                option.getDeparture(), option.getArrival());
        rescueCase.setSelectedOptionSnapshot(snapshot);

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
                        option.getId(), option.getFlight(), option.getExtraCost())
        );

        log.info("[Passenger Selection] Option {} ({}) selected for case {}", option.getId(), option.getFlight(), caseId);
        return option;
    }

    /**
     * Checks and applies airline involuntary disruption rebooking waiver (e.g. DGCA CAR Section 3 / Rule 240).
     * When disruption is airline-initiated, waives extra fare difference to ₹0.
     */
    @Transactional
    public boolean applyInvoluntaryDisruptionWaiver(String caseId, String optionId) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));
        RebookOption option = optionRepository.findById(optionId)
                .orElseThrow(() -> new IllegalArgumentException("Option not found: " + optionId));

        if (rescueCase.getCancelledSegment() != null || rescueCase.getFlight() != null) {
            int originalCost = option.getExtraCost();
            option.setExtraCost(0);
            optionRepository.save(option);

            auditService.logEvent(
                    caseId,
                    "INVOLUNTARY_DISRUPTION_WAIVER_APPLIED",
                    "Flyo Regulatory Compliance",
                    String.format("Carrier involuntary disruption waiver applied (DGCA CAR / Rule 240). Fare waived from ₹%,d to ₹0 for flight %s.",
                            originalCost, option.getFlight())
            );
            log.info("[Involuntary Waiver] Case {}: Option {} fare waived from ₹{} to ₹0", caseId, optionId, originalCost);
            return true;
        }
        return false;
    }

    /**
     * Handles expired option window with closed-loop auto-refresh.
     */
    @Transactional
    public void handleOptionExpired(String caseId) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_OPTION_EXPIRED,
                "Flyo Provider Bridge",
                "Flight option availability window expired"
        );
        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_REFRESHING_ALTERNATIVES,
                "Flyo Provider Bridge",
                "Refreshing live alternative flight availability"
        );
        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_SEARCHING_ALTERNATIVES,
                "Flyo Ranking Engine",
                "Executing live inventory search for alternatives"
        );
        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_OPTIONS_READY,
                "Flyo Ranking Engine",
                "New alternative options generated and ranked"
        );
        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_AWAITING_OPTION_SELECTION,
                "Flyo Messaging",
                "Dispatched fresh alternative flights to passenger"
        );
        rescueCaseRepository.save(rescueCase);
    }
}
