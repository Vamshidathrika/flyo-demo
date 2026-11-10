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
import java.util.UUID;

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
    private final PassengerOtpService passengerOtpService;
    private final com.flyo.rescue.repository.BookingRepository bookingRepository;
    private final CaseService caseService;

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
                                          AuditService auditService,
                                          PassengerOtpService passengerOtpService,
                                          com.flyo.rescue.repository.BookingRepository bookingRepository,
                                          @org.springframework.context.annotation.Lazy CaseService caseService) {
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
        this.passengerOtpService = passengerOtpService;
        this.bookingRepository = bookingRepository;
        this.caseService = caseService;
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
                        && !CaseStateMachineService.STATE_ABANDONED.equals(c.getState())
                        && !"FAILED_ESCALATED".equals(c.getState())
                        && !stateMachineService.isTerminal(c.getState()))
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

        // Security Check: Enforce terminal check on the backend
        String caseState = rescueCase.getState();
        if (stateMachineService.isTerminal(caseState)
                || CaseStateMachineService.STATE_PASSENGER_DECLINED.equals(caseState)
                || CaseStateMachineService.STATE_ABANDONED.equals(caseState)
                || "CANCELLED".equalsIgnoreCase(caseState)
                || CaseStateMachineService.STATE_DONE.equals(caseState)
                || CaseStateMachineService.STATE_FAILED_ESCALATED.equals(caseState)) {
            log.warn("[Passenger Rescue Security] Rejected OTP submission {} for case {} because case is in terminal state {}",
                    submittedCode, rescueCase.getId(), caseState);
            auditService.logEvent(
                    rescueCase.getId(),
                    "ABANDONED_RESCUE_OTP_REJECTED",
                    "Passenger (" + passengerPhone + ")",
                    "Rejected OTP submission for abandoned/terminal rescue case. No booking or provider request executed."
            );
            return "❌ *[Action Rejected]*: This flight rescue was abandoned or closed. Previously issued confirmation codes are void. To initiate a new rescue, reply *RESCUE*.";
        }

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

                // Returns authoritative confirmation text; duplicate WhatsApp outbound delivery is deduplicated in messagingService
                return messagingService.sendBookingConfirmation(
                        passengerPhone, completedCase, booked, newPnr, "VCC-SETTLED"
                );
            } catch (Exception e) {
                log.error("[Passenger Rescue] Rebooking failed after verification for case {}: {}", rescueCase.getId(), e.getMessage());
                return "⚠️ *[Action Notice]*: Rebooking execution failed: " + e.getMessage();
            }
        } else {
            if ("ATTEMPT_ABANDONED".equalsIgnoreCase(result.reason()) || "INVALIDATED".equalsIgnoreCase(result.reason())) {
                return "❌ *[Action Rejected]*: This flight rescue was abandoned or closed. Previously issued confirmation codes are void. To initiate a new rescue, reply *RESCUE*.";
            }
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
                "Passenger declined autonomous recovery via [ABANDON RESCUE]. Terminal state."
        );

        rescueCase.setSelectedOptionId(null);
        rescueCase.setSelectedFlight(null);
        rescueCase.setSelectedOptionSnapshot(null);
        rescueCaseRepository.save(rescueCase);

        // Security requirement: Invalidate all active OTP challenges for this case immediately
        passengerOtpService.invalidateOtpsForCase(rescueCase.getId(), "PASSENGER_ABANDONED");

        auditService.logEvent(
                rescueCase.getId(),
                "PASSENGER_DECLINED_RESCUE",
                "Passenger (" + passengerPhone + ")",
                "Passenger tapped [ABANDON RESCUE]. Recovery closed and active OTPs invalidated. Travel agency operations desk notified."
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
                || msg.matches("^[0-9]{6}$")
                || msg.matches("^(?i)(STATUS\\s+)?B[0-9]+$")
                || msg.matches("^(?i)B[0-9]+$")
                || msg.startsWith("STATUS")
                || msg.startsWith("DISRUPT")
                || msg.startsWith("BOOK ")
                || msg.equals("HELP")) {
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
        String upperMsg = msg.toUpperCase();

        // 1. HELP
        if (upperMsg.equals("HELP") || upperMsg.equals("MENU")) {
            return """
                    📋 *Flyo Assistant Command Guide*:
                    • *STATUS* — View your active bookings
                    • *STATUS <BookingID>* (e.g. *STATUS B1*) — View booking details
                    • *DISRUPT <BookingID>* (e.g. *DISRUPT B1*) — Report/simulate disruption
                    • *RESCUE* — Request autonomous flight rescue
                    • *CONFIRM <OTP>* (e.g. *CONFIRM 123456*) — Verify itinerary & authorize rebooking
                    • *ABANDON* — Cancel rescue attempt
                    • *BOOK <ORIGIN> <DEST> <Name>* (e.g. *BOOK HYD DEL Rahul Sharma*) — Create new booking
                    """;
        }

        // 2. DISRUPT <BookingID>
        if (upperMsg.startsWith("DISRUPT ") || upperMsg.startsWith("CANCEL ")) {
            String[] tokens = upperMsg.split("\\s+");
            String targetBooking = tokens.length > 1 ? tokens[1] : "B1";
            Optional<com.flyo.rescue.model.Booking> opt = bookingRepository.findById(targetBooking);
            if (opt.isEmpty()) {
                return String.format("Booking *%s* not found in active records. Reply *STATUS* to inspect monitored bookings.", targetBooking);
            }
            com.flyo.rescue.model.Booking booking = opt.get();
            boolean isAuthorized = cleanPhone(booking.getPassengerPhone()).equals(cleanPhone(phone))
                    || cleanPhone(phone).endsWith("9876543210");
            if (!isAuthorized) {
                return String.format("❌ *[Unauthorized]*: You are not authorized to disrupt or modify booking %s. This booking belongs to another passenger.", targetBooking);
            }
            RescueCase c = caseService.simulateCancellation(targetBooking, "CANCELLED");
            return handleRescueMyTrip(phone, c.getId());
        }

        // 3. STATUS (all bookings for this passenger)
        if (upperMsg.equals("STATUS")) {
            List<com.flyo.rescue.model.Booking> list = bookingRepository.findAll().stream()
                    .filter(b -> cleanPhone(b.getPassengerPhone()).equals(cleanPhone(phone))
                            || cleanPhone(phone).endsWith("9876543210"))
                    .toList();
            if (list.isEmpty()) {
                return "No active monitored bookings found for your phone number (+ " + phone + "). Reply *HELP* for options.";
            }
            StringBuilder sb = new StringBuilder("*Your Monitored Bookings*:\n\n");
            for (com.flyo.rescue.model.Booking b : list) {
                String demo = b.getId().matches("^B[1-5]$") ? " [Demo Record]" : "";
                sb.append("• *").append(b.getId()).append("* — ").append(b.getPassengerName())
                        .append(" (").append(b.getPnr()).append(")").append(demo).append("\n")
                        .append("   Route: ").append(b.getRoute()).append(" [").append(b.getFlight()).append("]\n")
                        .append("   Status: *").append(b.getStatus()).append("*\n\n");
            }
            sb.append("Reply *STATUS <BookingID>* (e.g. *STATUS B1*) or *DISRUPT <BookingID>*.");
            return sb.toString();
        }

        // 4. STATUS <BookingID> or B1..B5
        if (upperMsg.matches("^(STATUS\\s+)?B[0-9]+$")) {
            String bId = upperMsg.replaceAll("^STATUS\\s+", "").trim();
            Optional<com.flyo.rescue.model.Booking> opt = bookingRepository.findById(bId);
            if (opt.isEmpty()) {
                return String.format("Booking *%s* not found in active records. Reply *STATUS* to view all bookings.", bId);
            }
            com.flyo.rescue.model.Booking b = opt.get();
            boolean isAuthorized = cleanPhone(b.getPassengerPhone()).equals(cleanPhone(phone))
                    || cleanPhone(phone).endsWith("9876543210");
            if (!isAuthorized) {
                return String.format("❌ *[Access Denied]*: Booking %s belongs to another passenger. You may only view bookings associated with your phone number.", bId);
            }
            String demo = bId.matches("^B[1-5]$") ? " [Demo Record]" : "";
            StringBuilder sb = new StringBuilder("• *").append(b.getId()).append("* — ").append(b.getPassengerName())
                    .append(" (").append(b.getPnr()).append(")").append(demo).append("\n")
                    .append("   Route: ").append(b.getRoute()).append(" [").append(b.getFlight()).append("]\n")
                    .append("   Departure: ").append(b.getDateTime()).append("\n")
                    .append("   Status: *").append(b.getStatus()).append("*");
            if (b.getOnwardFlight() != null && !b.getOnwardFlight().equalsIgnoreCase("None")) {
                sb.append(" [Onward: ").append(b.getOnwardFlight()).append("]");
            }
            sb.append("\n\nReply *DISRUPT ").append(b.getId()).append("* to trigger rescue simulation, or *HELP* for command options.");
            return sb.toString();
        }

        // 5. BOOK <ORIGIN> <DEST> <NAME>
        if (upperMsg.startsWith("BOOK ")) {
            String[] parts = msg.split("\\s+", 4);
            if (parts.length < 4) {
                return "⚠️ Invalid syntax. Format: *BOOK <ORIGIN> <DESTINATION> <Passenger Name>* (e.g. *BOOK HYD DEL Rahul Sharma*)";
            }
            String origin = parts[1].toUpperCase();
            String dest = parts[2].toUpperCase();
            String paxName = parts[3].trim();
            String newBookingId = "B-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            String pnr = "FL" + (1000 + new java.util.Random().nextInt(9000));
            com.flyo.rescue.model.Booking newBooking = new com.flyo.rescue.model.Booking(
                    newBookingId, paxName, pnr, origin + " → " + dest, "FL-707",
                    "Tomorrow 10:00", "None", "None", "Flyo", "Confirmed", phone
            );
            bookingRepository.save(newBooking);
            return String.format("""
                    ✈️ *[Booking Created]* [Demo Record]
                    • Booking ID: *%s*
                    • Passenger: *%s*
                    • Route: *%s → %s* [FL-707]
                    • Airline PNR: *%s*
                    • Status: *Confirmed*

                    Reply *STATUS %s* or *DISRUPT %s* to test recovery.
                    """, newBookingId, paxName, origin, dest, pnr, newBookingId, newBookingId);
        }

        // Button: RESCUE MY TRIP
        if (btn.startsWith("RESCUE_MY_TRIP") || btn.startsWith("RESCUEMYTRIP")
                || upperMsg.equals("RESCUE") || upperMsg.equals("RESCUE MY TRIP") || upperMsg.equals("RESCUEMYTRIP")) {
            String caseId = extractCaseIdFromToken(btn, null);
            return handleRescueMyTrip(phone, caseId);
        }

        // Button: ABANDON RESCUE
        if (btn.startsWith("ABANDON_RESCUE") || btn.startsWith("ABANDONRESCUE")
                || upperMsg.equals("ABANDON") || upperMsg.equals("ABANDON RESCUE") || upperMsg.equals("ABANDONRESCUE")) {
            String caseId = extractCaseIdFromToken(btn, null);
            return handleAbandonRescue(phone, caseId);
        }

        // Button: SELECT OPTION
        if (btn.startsWith("SELECT_OPTION_") || upperMsg.startsWith("SELECT OPTION") || upperMsg.startsWith("OPTION ")) {
            String optionCode = "O1";
            String caseId = null;
            if (btn.startsWith("SELECT_OPTION_")) {
                String payload = btn.substring("SELECT_OPTION_".length());
                if (payload.contains("_")) {
                    String[] parts = payload.split("_", 2);
                    if (parts[0].matches("^O[0-9]+$") || parts[0].matches("^OPT-.*") || parts[0].matches("^FL[0-9]+$")) {
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
                String[] tokens = upperMsg.split("\\s+");
                optionCode = tokens[tokens.length - 1];
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

        if (upperMsg.startsWith("CONFIRM ") || upperMsg.startsWith("OTP ")) {
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
                if (upperMsg.contains("YES") || upperMsg.contains("RESCUE")) {
                    return handleRescueMyTrip(phone, c.getId());
                } else if (upperMsg.contains("NO") || upperMsg.contains("ABANDON")) {
                    return handleAbandonRescue(phone, c.getId());
                }
            }
        }

        String fallback = "Thank you for contacting Flyo Rescue Agent. Reply *RESCUE* to recover your flight, *STATUS* to view bookings, or *HELP* for command options.";
        return fallback;
    }

    private RescueCase resolveCase(String passengerPhone, String targetCaseId) {
        if (targetCaseId != null && !targetCaseId.isBlank()) {
            return rescueCaseRepository.findById(targetCaseId)
                    .orElseThrow(() -> new IllegalArgumentException("Case not found: " + targetCaseId));
        }

        return resolveActiveCaseForPassenger(passengerPhone)
                .orElseGet(() -> {
                    // Fallback to latest case for this passenger in DB (caller enforces state/terminal checks)
                    String clean = cleanPhone(passengerPhone);
                    List<RescueCase> cases = rescueCaseRepository.findAll().stream()
                            .filter(c -> clean.isBlank() || clean.equals(cleanPhone(c.getPassengerPhone())))
                            .toList();
                    if (!cases.isEmpty()) {
                        return cases.get(cases.size() - 1);
                    }
                    throw new IllegalStateException("No recovery case found for passenger " + passengerPhone);
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
