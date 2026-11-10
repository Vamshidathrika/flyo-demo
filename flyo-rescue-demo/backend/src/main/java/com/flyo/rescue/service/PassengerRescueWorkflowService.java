package com.flyo.rescue.service;

import com.flyo.rescue.model.PassengerOtpChallenge;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.PassengerOtpChallengeRepository;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Main Coordinator for the Passenger-First WhatsApp Flight Recovery Workflow.
 *
 * Implements the complete passenger journey:
 * 1. RESCUE MY TRIP -> Acknowledgment -> Itinerary Analysis -> Alternative Search -> Options Display
 * 2. Option Selection -> Itinerary Preview -> Confirmation OTP Challenge (Scenario A or B)
 * 3. OTP Submission -> Verification -> Payment Authorization -> Rebooking Execution -> Authoritative PNR -> Success Message
 * 4. ABANDON RESCUE -> Case Closed as PASSENGER_DECLINED -> Travel Desk Notified
 */
@Service
public class PassengerRescueWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(PassengerRescueWorkflowService.class);

    private final RescueCaseRepository rescueCaseRepository;
    private final RebookOptionRepository optionRepository;
    private final PassengerOtpChallengeRepository challengeRepository;
    private final PassengerWhatsAppMessagingService messagingService;
    private final PassengerItineraryService itineraryService;
    private final AlternativeFlightSearchService searchService;
    private final PassengerOptionSelectionService optionSelectionService;
    private final PassengerConfirmationService confirmationService;
    private final RebookingOrchestrationService rebookingService;
    private final CaseStateMachineService stateMachineService;
    private final AuditService auditService;

    public PassengerRescueWorkflowService(RescueCaseRepository rescueCaseRepository,
                                          RebookOptionRepository optionRepository,
                                          PassengerOtpChallengeRepository challengeRepository,
                                          PassengerWhatsAppMessagingService messagingService,
                                          PassengerItineraryService itineraryService,
                                          AlternativeFlightSearchService searchService,
                                          PassengerOptionSelectionService optionSelectionService,
                                          PassengerConfirmationService confirmationService,
                                          RebookingOrchestrationService rebookingService,
                                          CaseStateMachineService stateMachineService,
                                          AuditService auditService) {
        this.rescueCaseRepository = rescueCaseRepository;
        this.optionRepository = optionRepository;
        this.challengeRepository = challengeRepository;
        this.messagingService = messagingService;
        this.itineraryService = itineraryService;
        this.searchService = searchService;
        this.optionSelectionService = optionSelectionService;
        this.confirmationService = confirmationService;
        this.rebookingService = rebookingService;
        this.stateMachineService = stateMachineService;
        this.auditService = auditService;
    }

    /**
     * Resolves the active passenger case for a given phone number.
     */
    public Optional<RescueCase> resolveActiveCaseForPassenger(String passengerPhone) {
        String clean = cleanPhone(passengerPhone);
        return rescueCaseRepository.findAll().stream()
                .filter(c -> clean.equals(cleanPhone(c.getPassengerPhone()))
                        && !"DONE".equals(c.getState())
                        && !"PASSENGER_DECLINED".equals(c.getState())
                        && !"FAILED_ESCALATED".equals(c.getState()))
                .reduce((first, second) -> second); // Latest active case
    }

    /**
     * Step 3: Handles passenger clicking [RESCUE MY TRIP].
     */
    @Transactional
    public String handleRescueMyTrip(String passengerPhone, String targetCaseId) {
        RescueCase rescueCase = resolveCase(passengerPhone, targetCaseId);

        rescueCase.setPassengerDecision("RESCUE_REQUESTED");
        rescueCaseRepository.save(rescueCase);

        auditService.logEvent(
                rescueCase.getId(),
                "PASSENGER_DECISION_RESCUE",
                "Passenger (" + passengerPhone + ")",
                "Passenger tapped [RESCUE MY TRIP]. Initiating autonomous itinerary recovery."
        );

        // Immediate acknowledgment
        messagingService.sendRescueAcknowledgment(passengerPhone, rescueCase.getId());

        // Step 4: Fetch itinerary and analyze downstream risks
        itineraryService.fetchAndAnalyzeItinerary(rescueCase.getId());

        // Step 5: Search and rank alternative flights
        List<RebookOption> options = searchService.searchAndRankAlternatives(rescueCase.getId());

        // Step 6: Display alternative flights on WhatsApp
        rescueCase = rescueCaseRepository.findById(rescueCase.getId()).orElse(rescueCase);
        return messagingService.sendAlternativeFlightOptions(passengerPhone, rescueCase, options);
    }

    /**
     * Step 7 & 8: Handles passenger selecting an alternative flight option.
     */
    @Transactional
    public String handleOptionSelected(String passengerPhone, String targetCaseId, String optionCode) {
        RescueCase rescueCase = resolveCase(passengerPhone, targetCaseId);

        // Step 7: Select option & pre-validate
        RebookOption selectedOption = optionSelectionService.selectOption(rescueCase.getId(), optionCode);

        // Step 8: Arm single-use Passenger Confirmation OTP
        String otpCode = confirmationService.requestConfirmationOtp(rescueCase.getId(), passengerPhone);

        rescueCase = rescueCaseRepository.findById(rescueCase.getId()).orElse(rescueCase);

        // Send preview and OTP prompt
        return messagingService.sendItineraryPreviewAndOtpRequest(passengerPhone, rescueCase, selectedOption, otpCode);
    }

    /**
     * Step 8, 9, 10 & 11: Handles passenger submitting their 6-digit confirmation OTP.
     */
    @Transactional
    public String handleOtpSubmission(String passengerPhone, String targetCaseId, String submittedCode) {
        RescueCase rescueCase = resolveCase(passengerPhone, targetCaseId);

        PassengerOtpService.PassengerOtpResult result = confirmationService.confirmAndVerify(
                rescueCase.getId(), passengerPhone, submittedCode
        );

        if (result.valid()) {
            // Steps 9, 10 & 11: Execute rebooking workflow (with payment authorization if positive fare)
            try {
                RescueCase completedCase = rebookingService.executeRebookingWorkflow(rescueCase.getId(), passengerPhone);

                if (CaseStateMachineService.STATE_BOOKING_FAILED.equals(completedCase.getState())
                        || CaseStateMachineService.STATE_PAYMENT_RECONCILIATION_REQUIRED.equals(completedCase.getState())) {
                    return "⚠️ *[Action Notice]*: Rebooking execution failed: " + completedCase.getError();
                }

                String newPnr = completedCase.getNewPnr();
                RebookOption booked = optionRepository.findById(completedCase.getApprovedOptionId()).orElse(null);

                return messagingService.sendBookingConfirmation(
                        passengerPhone, completedCase, booked, newPnr, "VCC-SETTLED"
                );
            } catch (Exception e) {
                log.error("[Passenger Rescue] Rebooking failed after verification for case {}: {}", rescueCase.getId(), e.getMessage());
                return "⚠️ *[Action Notice]*: Rebooking execution failed: " + e.getMessage();
            }
        } else {
            // Send failure notification with guidance
            return messagingService.sendOtpFailedNotice(passengerPhone, result.reason());
        }
    }

    /**
     * Step 12: Handles passenger clicking [ABANDON RESCUE].
     */
    @Transactional
    public String handleAbandonRescue(String passengerPhone, String targetCaseId) {
        RescueCase rescueCase = resolveCase(passengerPhone, targetCaseId);

        rescueCase.setPassengerDecision("ABANDON_RESCUE");

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_PASSENGER_DECLINED,
                "Passenger (" + passengerPhone + ")",
                "Passenger declined autonomous recovery via [ABANDON RESCUE]. Closed without booking changes."
        );

        rescueCaseRepository.save(rescueCase);

        auditService.logEvent(
                rescueCase.getId(),
                "PASSENGER_DECLINED_RESCUE",
                "Passenger (" + passengerPhone + ")",
                "Passenger tapped [ABANDON RESCUE]. Recovery closed. Travel agency operations desk notified."
        );

        log.info("[Passenger Rescue] Passenger {} abandoned rescue for case {}", passengerPhone, rescueCase.getId());
        return messagingService.sendAbandonConfirmation(passengerPhone);
    }

    /**
     * Checks if an incoming WhatsApp message or callback belongs to a passenger workflow.
     */
    public boolean isPassengerMessage(String phone, String text, String buttonId) {
        String btn = buttonId != null ? buttonId.toUpperCase().trim() : "";
        String msg = text != null ? text.toUpperCase().trim() : "";

        if (btn.startsWith("RESCUE_MY_TRIP") || btn.startsWith("RESCUEMYTRIP")
                || btn.startsWith("ABANDON_RESCUE") || btn.startsWith("ABANDONRESCUE")
                || btn.startsWith("SELECT_OPTION_") || btn.startsWith("VERIFY_PASSENGER_OTP_")) {
            return true;
        }

        if (msg.equals("RESCUE") || msg.equals("RESCUE MY TRIP") || msg.equals("RESCUE_MY_TRIP") || msg.equals("RESCUEMYTRIP")
                || msg.equals("ABANDON") || msg.equals("ABANDON RESCUE") || msg.equals("ABANDON_RESCUE") || msg.equals("ABANDONRESCUE")
                || msg.startsWith("SELECT OPTION") || msg.startsWith("OPTION ")
                || msg.matches("^[0-9]{6}$")) {
            return true;
        }

        // Check if there is an active passenger case awaiting passenger input
        return resolveActiveCaseForPassenger(phone).map(c -> {
            String s = c.getState();
            return CaseStateMachineService.STATE_AWAITING_PASSENGER_DECISION.equals(s)
                    || CaseStateMachineService.STATE_AWAITING_OPTION_SELECTION.equals(s)
                    || CaseStateMachineService.STATE_AWAITING_PASSENGER_CONFIRMATION.equals(s)
                    || CaseStateMachineService.STATE_AWAITING_PASSENGER_OTP.equals(s)
                    || CaseStateMachineService.STATE_OTP_FAILED.equals(s);
        }).orElse(false);
    }

    /**
     * Processes inbound passenger message or button callback.
     */
    public String processPassengerInbound(String phone, String text, String buttonId) {
        String btn = buttonId != null ? buttonId.trim() : "";
        String msg = text != null ? text.trim() : "";

        // Button: RESCUE MY TRIP
        if (btn.startsWith("RESCUE_MY_TRIP") || btn.startsWith("RESCUEMYTRIP")
                || msg.equalsIgnoreCase("RESCUE") || msg.equalsIgnoreCase("RESCUE MY TRIP") || msg.equalsIgnoreCase("RESCUEMYTRIP")) {
            String caseId = extractCaseIdFromToken(btn, null);
            return handleRescueMyTrip(phone, caseId);
        }

        // Button: ABANDON RESCUE
        if (btn.startsWith("ABANDON_RESCUE") || btn.startsWith("ABANDONRESCUE")
                || msg.equalsIgnoreCase("ABANDON") || msg.equalsIgnoreCase("ABANDON RESCUE") || msg.equalsIgnoreCase("ABANDONRESCUE")) {
            String caseId = extractCaseIdFromToken(btn, null);
            return handleAbandonRescue(phone, caseId);
        }

        // Button: SELECT OPTION
        if (btn.startsWith("SELECT_OPTION_") || msg.toUpperCase().startsWith("SELECT OPTION") || msg.toUpperCase().startsWith("OPTION ")) {
            String optionCode = "O1";
            String caseId = null;
            if (btn.startsWith("SELECT_OPTION_")) {
                String payload = btn.substring("SELECT_OPTION_".length());
                if (payload.contains("_")) {
                    String[] parts = payload.split("_", 2);
                    if (parts[0].matches("^O[0-9]+$") || parts[0].matches("^OPT-.*")) {
                        optionCode = parts[0];
                        caseId = parts[1];
                    } else {
                        caseId = parts[0];
                        optionCode = parts[1];
                    }
                } else {
                    optionCode = payload;
                }
            } else {
                String[] tokens = msg.split("\\s+");
                optionCode = tokens[tokens.length - 1].toUpperCase();
                if (optionCode.equals("1")) optionCode = "O1";
                else if (optionCode.equals("2")) optionCode = "O2";
                else if (optionCode.equals("3")) optionCode = "O3";
            }
            return handleOptionSelected(phone, caseId, optionCode);
        }

        // Button or Text: 6-digit OTP Submission
        if (btn.startsWith("VERIFY_PASSENGER_OTP_")) {
            String payload = btn.substring("VERIFY_PASSENGER_OTP_".length());
            String caseId = null;
            String code = payload;
            if (payload.contains("_")) {
                String[] parts = payload.split("_", 2);
                if (parts[0].matches("^[0-9]{6}$")) {
                    code = parts[0];
                    caseId = parts[1];
                } else {
                    caseId = parts[0];
                    code = parts[1];
                }
            }
            return handleOtpSubmission(phone, caseId, code);
        }

        if (msg.matches("^[0-9]{6}$")) {
            return handleOtpSubmission(phone, null, msg);
        }

        if (msg.toUpperCase().startsWith("CONFIRM ") || msg.toUpperCase().startsWith("OTP ")) {
            String[] tokens = msg.split("\\s+");
            String code = tokens[tokens.length - 1];
            if (code.matches("^[0-9]{6}$")) {
                return handleOtpSubmission(phone, null, code);
            }
        }

        // Context-aware fallback for active passenger case
        Optional<RescueCase> activeCaseOpt = resolveActiveCaseForPassenger(phone);
        if (activeCaseOpt.isPresent()) {
            RescueCase c = activeCaseOpt.get();
            if (CaseStateMachineService.STATE_AWAITING_PASSENGER_DECISION.equals(c.getState())) {
                if (msg.toUpperCase().contains("YES") || msg.toUpperCase().contains("RESCUE")) {
                    return handleRescueMyTrip(phone, c.getId());
                } else if (msg.toUpperCase().contains("NO") || msg.toUpperCase().contains("ABANDON")) {
                    return handleAbandonRescue(phone, c.getId());
                }
            }
        }

        String fallback = "Thank you for contacting Flyo Rescue Agent. Reply *RESCUE* to recover your flight or *ABANDON* to close.";
        messagingService.sendAbandonConfirmation(phone);
        return fallback;
    }

    private RescueCase resolveCase(String passengerPhone, String targetCaseId) {
        if (targetCaseId != null && !targetCaseId.isBlank()) {
            return rescueCaseRepository.findById(targetCaseId)
                    .orElseThrow(() -> new IllegalArgumentException("Case not found: " + targetCaseId));
        }

        return resolveActiveCaseForPassenger(passengerPhone)
                .orElseGet(() -> {
                    // Fallback to latest case in DB
                    List<RescueCase> cases = rescueCaseRepository.findAll();
                    if (!cases.isEmpty()) {
                        return cases.get(cases.size() - 1);
                    }
                    throw new IllegalStateException("No active recovery case found for passenger " + passengerPhone);
                });
    }

    private String extractCaseIdFromToken(String token, String defaultVal) {
        if (token == null) return defaultVal;
        String[] parts = token.split("_");
        for (String p : parts) {
            if (p.startsWith("CASE-") || p.matches("^C[0-9]+$")) {
                return p;
            }
        }
        return defaultVal;
    }

    private String cleanPhone(String phone) {
        if (phone == null) return "";
        return phone.replaceAll("[^0-9]", "");
    }
}
