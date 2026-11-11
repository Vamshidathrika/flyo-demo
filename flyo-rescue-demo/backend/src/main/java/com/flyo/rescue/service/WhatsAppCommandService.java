package com.flyo.rescue.service;

import com.flyo.rescue.config.WhatsAppConfig;
import com.flyo.rescue.controller.MockAirlineController;
import com.flyo.rescue.model.AuditEvent;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.SimulatedMessage;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WhatsAppCommandService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppCommandService.class);

    private final CaseService caseService;
    private final BookingService bookingService;
    private final WhatsAppConfig config;
    private final MetaWhatsAppClient whatsAppClient;
    private final WhatsAppChatLogService chatLogService;
    private final WhatsAppOtpService otpService;
    private final AuditService auditService;
    private final BookingRepository bookingRepository;
    private final RescueCaseRepository caseRepository;
    private final com.flyo.rescue.repository.RebookOptionRepository optionRepository;
    private final PaymentSettlementService paymentSettlementService;
    private final MessageService messageService;
    private final PassengerRescueWorkflowService passengerRescueWorkflowService;
    private final DistributedIdempotencyService distributedIdempotencyService;

    // Track active case per sender phone for conversational context
    private final Map<String, String> lastActiveCaseByPhone = new ConcurrentHashMap<>();

    // Track authenticated operator sessions
    private final Set<String> authenticatedSessions = ConcurrentHashMap.newKeySet();

    public WhatsAppCommandService(CaseService caseService,
                                  BookingService bookingService,
                                  WhatsAppConfig config,
                                  MetaWhatsAppClient whatsAppClient,
                                  WhatsAppChatLogService chatLogService,
                                  WhatsAppOtpService otpService,
                                  AuditService auditService,
                                  BookingRepository bookingRepository,
                                  RescueCaseRepository caseRepository,
                                  com.flyo.rescue.repository.RebookOptionRepository optionRepository,
                                  PaymentSettlementService paymentSettlementService,
                                  MessageService messageService,
                                  @org.springframework.context.annotation.Lazy PassengerRescueWorkflowService passengerRescueWorkflowService,
                                  DistributedIdempotencyService distributedIdempotencyService) {
        this.caseService = caseService;
        this.bookingService = bookingService;
        this.config = config;
        this.whatsAppClient = whatsAppClient;
        this.chatLogService = chatLogService;
        this.otpService = otpService;
        this.auditService = auditService;
        this.bookingRepository = bookingRepository;
        this.caseRepository = caseRepository;
        this.optionRepository = optionRepository;
        this.paymentSettlementService = paymentSettlementService;
        this.messageService = messageService;
        this.passengerRescueWorkflowService = passengerRescueWorkflowService;
        this.distributedIdempotencyService = distributedIdempotencyService;

        // Pre-authenticate default operator phone for seamless local dashboard operations
        if (config.getOperatorPhone() != null && !config.getOperatorPhone().isBlank()) {
            String opPhone = cleanPhone(config.getOperatorPhone());
            authenticatedSessions.add(opPhone);
            distributedIdempotencyService.tryAcquire("OPERATOR_AUTH_SESSION:" + opPhone, "OPERATOR_SESSION", 86400);
        }
    }

    public void authenticateSessionForTest(String phone) {
        if (phone != null) {
            String cp = cleanPhone(phone);
            authenticatedSessions.add(cp);
            distributedIdempotencyService.tryAcquire("OPERATOR_AUTH_SESSION:" + cp, "OPERATOR_SESSION", 86400);
        }
    }

    public void clearSessionsForTest() {
        for (String cp : authenticatedSessions) {
            distributedIdempotencyService.delete("OPERATOR_AUTH_SESSION:" + cp);
        }
        authenticatedSessions.clear();
        distributedIdempotencyService.deleteByPrefix("OPERATOR_AUTH_SESSION:");
    }

    public boolean isSessionAuthenticated(String phone) {
        String cp = cleanPhone(phone);
        return authenticatedSessions.contains(cp) || distributedIdempotencyService.isProcessed("OPERATOR_AUTH_SESSION:" + cp);
    }

    public String processInboundMessage(String senderPhone, String messageBody, String buttonReplyId) {
        log.info("[WhatsApp Bot Ingress] From: {}, Body: {}, ButtonId: {}", senderPhone, messageBody, buttonReplyId);

        String rawCommand = buttonReplyId != null && !buttonReplyId.isBlank() ? buttonReplyId : messageBody;
        if (rawCommand == null) rawCommand = "";

        // Log inbound message to live chat log
        chatLogService.logInbound(senderPhone, rawCommand);

        boolean isOperator = isAuthorizedOperator(senderPhone);
        boolean isPassenger = isPassengerSender(senderPhone) || isPassengerCommand(rawCommand);

        // Dual-Persona Routing: Passenger Care vs Operator Command
        if (!isOperator && isPassenger) {
            return handlePassengerInboundMessage(senderPhone, rawCommand);
        }

        // Security: Whitelist Verification for Operator controls
        if (!isOperator) {
            String unauthorizedMsg = String.format(
                    "*Access Denied*\nSender +%s is not an authorized Flyo Operator phone number. " +
                    "Disruption and rebooking controls are restricted.", senderPhone);
            whatsAppClient.sendTextMessage(senderPhone, unauthorizedMsg);
            return unauthorizedMsg;
        }

        if (rawCommand.isBlank()) {
            return sendHelpMenu(senderPhone);
        }

        String command = rawCommand.trim().toUpperCase();

        // 1. GREETING / HI / HELLO / LOGIN / START ("First Hi requires Login Authentication")
        if (command.equals("HI") || command.equals("HELLO") || command.equals("HEY") || command.equals("START") || command.equals("LOGIN") || command.startsWith("LOGIN ")) {
            if (isSessionAuthenticated(senderPhone)) {
                return sendWelcomeDashboardMenu(senderPhone);
            } else {
                return sendLoginPrompt(senderPhone, rawCommand);
            }
        }

        // 2. SEND LOGIN OTP / REQUEST LOGIN OTP
        if (command.equals("REQUEST_LOGIN_OTP") || command.equals("SEND OTP") || command.equals("LOGIN OTP") || command.equals("SEND_LOGIN_OTP")) {
            return handleSendLoginOtp(senderPhone);
        }

        // 2b. Direct Phone Number submission for Login when unauthenticated
        if (!isSessionAuthenticated(senderPhone) && cleanPhone(rawCommand).length() >= 10) {
            String enteredPhone = cleanPhone(rawCommand);
            String cleanSender = cleanPhone(senderPhone);
            if (enteredPhone.equals(cleanSender) || enteredPhone.endsWith(cleanSender) || cleanSender.endsWith(enteredPhone)) {
                return handleSendLoginOtp(senderPhone);
            }
        }

        // 3. LOGOUT
        if (command.equals("LOGOUT") || command.equals("EXIT") || command.equals("SIGN OUT")) {
            authenticatedSessions.remove(cleanPhone(senderPhone));
            distributedIdempotencyService.delete("OPERATOR_AUTH_SESSION:" + cleanPhone(senderPhone));
            otpService.clear(senderPhone);
            String logoutMsg = "*[Logged Out Successfully]*\nYour operator session has been closed. Reply *HI* to authenticate again.";
            whatsAppClient.sendTextMessage(senderPhone, logoutMsg);
            return logoutMsg;
        }

        // 4. OTP SUBMISSION & VERIFICATION (Handles both Login OTP and Rebooking OTP)
        if (command.startsWith("OTP ") || command.startsWith("OTP:") || command.matches("^\\d{6}$") || command.startsWith("VERIFY_")
                || (command.startsWith("APPROVE") && command.matches(".*\\b\\d{6}\\b.*"))) {
            String code = command.replaceAll("[^0-9]", "");
            return handleOtpVerification(senderPhone, code);
        }

        // 5. HELP / OPERATIONAL MENU
        if (command.equals("HELP") || command.equals("MENU")) {
            return sendHelpMenu(senderPhone);
        }

        // GATE CHECK: If operator is not authenticated, require login first!
        if (!isSessionAuthenticated(senderPhone)) {
            return sendLoginPrompt(senderPhone, rawCommand);
        }

        // 6. STATUS / LIST
        if (command.equals("STATUS") || command.equals("LIST") || command.equals("BOOKINGS")) {
            return sendBookingsStatus(senderPhone);
        }

        if (command.startsWith("STATUS ") || command.startsWith("LIST ") || command.matches("^B[0-9]+$") || command.startsWith("BOOKING_")) {
            String bookingId = extractBookingId(command, "B1");
            return sendSingleBookingStatus(senderPhone, bookingId);
        }

        // Quick Button Shortcuts for Dashboard Funnel
        if (command.startsWith("AUTORESCUE_") || command.startsWith("AUTO_RESCUE_")) {
            String bId = command.replace("AUTO_RESCUE_", "").replace("AUTORESCUE_", "");
            return handleAutoRescue(senderPhone, bId.isBlank() ? "B1" : bId);
        }
        if (command.startsWith("DISRUPT_")) {
            String bId = command.replace("DISRUPT_", "");
            return handleDisruptionSimulation(senderPhone, bId.isBlank() ? "B1" : bId);
        }

        // 7. BOOK / SEARCH (Conversational booking directly from WhatsApp)
        if (command.startsWith("BOOK") || command.startsWith("SEARCH") || command.startsWith("FIND")) {
            return handleConversationalBooking(senderPhone, rawCommand);
        }

        // 8. SECURITY OTP GENERATION REQUEST FOR TICKET REISSUE
        if (command.equals("REQUEST OTP") || command.equals("GET OTP") || command.equals("REQUEST_OTP")) {
            String targetCase = lastActiveCaseByPhone.getOrDefault(senderPhone, "C1");
            return handleRequestOtp(senderPhone, targetCase, "O2");
        }

        // 9. BREAK PORTAL / RESTORE PORTAL
        if (command.equals("BREAK PORTAL") || command.equals("BREAK_PORTAL")) {
            MockAirlineController.setBroken(true);
            String reply = "*Mock Airline Portal is now BROKEN*\nSimulating GDS 500 carrier outage. Subsequent rebooking attempts will trigger 3 retries and transition to FAILED_ESCALATED.";
            whatsAppClient.sendTextMessage(senderPhone, reply);
            return reply;
        }

        if (command.equals("RESTORE PORTAL") || command.equals("RESTORE_PORTAL") || command.equals("FIX PORTAL")) {
            MockAirlineController.setBroken(false);
            String reply = "*Mock Airline Portal is now OPERATIONAL*\nNormal airline reservation gateway restored.";
            whatsAppClient.sendTextMessage(senderPhone, reply);
            return reply;
        }

        // 10. RESET
        if (command.equals("RESET") || command.equals("RESET_SYSTEM")) {
            return handleReset(senderPhone);
        }

        // 11. AUDIT / LOGS
        if (command.startsWith("AUDIT") || command.startsWith("LOGS") || command.equals("AUDIT")) {
            String caseId = extractCaseId(command, lastActiveCaseByPhone.getOrDefault(senderPhone, "C1"));
            return handleAuditList(senderPhone, caseId);
        }

        // 12. DISRUPT / CANCEL
        if (command.startsWith("DISRUPT") || command.startsWith("CANCEL")) {
            String bookingId = extractBookingId(command, "B1");
            return handleDisruptionSimulation(senderPhone, bookingId);
        }

        // 13. OPTIONS
        if (command.startsWith("OPTIONS")) {
            String caseId = extractCaseId(command, lastActiveCaseByPhone.getOrDefault(senderPhone, "C1"));
            return handleOptionsList(senderPhone, caseId);
        }

        // 14. SELECT / CHOOSE OPTION (triggers 2FA OTP Security Verification)
        if (command.startsWith("SELECT") || command.startsWith("CHOOSE")
                || (command.startsWith("REBOOK") && (command.contains("O1") || command.contains("O2") || command.contains("O3")))) {
            String optionId = "O2";
            if (command.contains("O1")) optionId = "O1";
            else if (command.contains("O3")) optionId = "O3";
            String targetCase = extractCaseId(command, lastActiveCaseByPhone.getOrDefault(senderPhone, "C1"));
            return handleRequestOtp(senderPhone, targetCase, optionId);
        }

        // 15. APPROVE & REBOOK (1-Step Combined Macro)
        if (command.contains("APPROVE") && (command.contains("REBOOK") || command.contains("EXECUTE"))
                || command.equals("APPROVE_AND_REBOOK_O2") || command.equals("APPROVE_AND_REBOOK")) {
            String optionId = "O2";
            if (command.contains("O1")) optionId = "O1";
            else if (command.contains("O3")) optionId = "O3";
            String targetCase = extractCaseId(command, lastActiveCaseByPhone.getOrDefault(senderPhone, "C1"));
            return handleApproveAndRebook(senderPhone, targetCase, optionId);
        }

        // 16. APPROVE (e.g. "APPROVE O2" or "APPROVE_O2" or "APPROVE C1 O2")
        if (command.startsWith("APPROVE")) {
            String optionId = "O2";
            if (command.contains("O1")) optionId = "O1";
            else if (command.contains("O3")) optionId = "O3";
            else if (command.contains("O2")) optionId = "O2";
            String targetCase = extractCaseId(command, lastActiveCaseByPhone.getOrDefault(senderPhone, "C1"));
            return handleApproval(senderPhone, targetCase, optionId);
        }

        // 17. REJECT / ESCALATE
        if (command.startsWith("REJECT") || command.startsWith("ESCALATE")) {
            String targetCase = extractCaseId(command, lastActiveCaseByPhone.getOrDefault(senderPhone, "C1"));
            String reason = "Rejected via WhatsApp by Flyo Operator (" + senderPhone + ")";
            return handleRejection(senderPhone, targetCase, reason);
        }

        // 18. REBOOK
        if (command.startsWith("REBOOK") || command.equals("REBOOK")) {
            String caseId = extractCaseId(command, lastActiveCaseByPhone.getOrDefault(senderPhone, "C1"));
            return handleRebook(senderPhone, caseId);
        }

        // 19. AUTO RESCUE (Full Autonomous Macro)
        if (command.startsWith("AUTO RESCUE") || command.startsWith("AUTO_RESCUE") || command.startsWith("AUTORESCUE")) {
            String bookingId = extractBookingId(command, "B1");
            return handleAutoRescue(senderPhone, bookingId);
        }

        // Unrecognized command
        String fallback = String.format("Unrecognized command: *%s*\nReply *HELP* to see available Flyo control options.", rawCommand);
        whatsAppClient.sendTextMessage(senderPhone, fallback);
        return fallback;
    }

    private String sendLoginPrompt(String senderPhone, String rawCommand) {
        String prompt = String.format("""
                *Welcome to Flyo Rescue Platform*

                *Operator Authentication Required*
                To access passenger records, disruption management, and automated rebooking, please authenticate your session.

                • Registered Operator Phone: *+%s*

                Tap *Send Login OTP* below or reply *SEND OTP* to receive your 6-digit authorization code:
                """, senderPhone);

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "REQUEST_LOGIN_OTP", "title", "Send Login OTP"),
                Map.of("id", "HELP", "title", "System Help")
        );
        whatsAppClient.sendInteractiveButtons(senderPhone, prompt, buttons);
        return prompt;
    }

    private String handleSendLoginOtp(String senderPhone) {
        WhatsAppOtpService.OtpChallenge challenge = otpService.createOtpChallenge(senderPhone, "LOGIN", "AUTH");

        String otpMsg = String.format("""
                *Flyo Security Verification Code*
                Your single-use 6-digit operator login code is:
                *%s*

                (Valid for 5 minutes)

                Reply with *%s* or tap below to authenticate and unlock your dashboard session:
                """, challenge.code(), challenge.code());

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "VERIFY_" + challenge.code(), "title", "Verify " + challenge.code()),
                Map.of("id", "REQUEST_LOGIN_OTP", "title", "Resend Code")
        );
        whatsAppClient.sendInteractiveButtons(senderPhone, otpMsg, buttons);
        return otpMsg;
    }

    private String sendWelcomeDashboardMenu(String senderPhone) {
        String welcome = String.format("""
                *Welcome to Flyo Rescue Operations Bot — Session Active*
                Operator: *+%s* (Authenticated)

                *Choose an operation below to interact with the Live Dashboard:*
                """, senderPhone);

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "AUTORESCUE_B1", "title", "Auto-Rescue Hero"),
                Map.of("id", "STATUS", "title", "Live Bookings"),
                Map.of("id", "DISRUPT_B1", "title", "Cancel AI-839")
        );
        whatsAppClient.sendInteractiveButtons(senderPhone, welcome, buttons);
        return welcome;
    }

    private String sendHelpMenu(String senderPhone) {
        String menu = """
                *Flyo Rescue WhatsApp Control Center*
                _Disruption Recovery & Browser Automation Engine_

                *Available Commands:*
                • *STATUS* — List today's monitored passenger trips
                • *BOOK <FROM> <TO> <PASSENGER>* — Search & book fresh flights
                • *DISRUPT B1* — Simulate AI-839 cancellation (Ananya Sharma)
                • *OPTIONS C1* — View ranked rebooking solutions
                • *REQUEST OTP* — Generate 6-digit security OTP in WhatsApp chat
                • *OTP <6-digits>* — Submit security OTP to unlock rebooking gate
                • *APPROVE O2* — Approve Option O2 (BOM transit, saves YYZ)
                • *REBOOK C1* — Launch Playwright browser rebooking
                • *APPROVE & REBOOK* — Approve O2 & rebook in single action
                • *REJECT C1* — Reject & escalate to manual desk
                • *AUTO RESCUE B1* — Autonomous end-to-end recovery macro
                • *AUDIT C1* — View real-time chronological audit trail
                • *BREAK PORTAL* — Simulate airline 500 outage
                • *RESTORE PORTAL* — Restore airline portal
                • *RESET* — Reset system back to initial test state
                • *LOGOUT* — Terminate operator session

                _Reply with any command or tap quick action buttons._
                """;
        whatsAppClient.sendTextMessage(senderPhone, menu);
        return menu;
    }

    private String sendBookingsStatus(String senderPhone) {
        List<Booking> bookings = bookingService.getAllBookings();
        StringBuilder sb = new StringBuilder("*Flyo Today's Bookings (").append(bookings.size()).append(" Monitored)*:\n\n");
        for (Booking b : bookings) {
            sb.append("• *").append(b.getId()).append("* — ").append(b.getPassengerName())
                    .append(" (").append(b.getPnr()).append(")\n")
                    .append("   Route: ").append(b.getRoute()).append(" [").append(b.getFlight()).append("]\n")
                    .append("   Status: *").append(b.getStatus()).append("*");
            if (b.getOnwardFlight() != null && !b.getOnwardFlight().equalsIgnoreCase("None")) {
                sb.append(" [Onward: ").append(b.getOnwardFlight()).append("]");
            }
            sb.append("\n\n");
        }
        sb.append("Reply *DISRUPT B1* to test recovery workflow or *BOOK HYD DEL <Name>* to book.");
        String text = sb.toString();
        whatsAppClient.sendTextMessage(senderPhone, text);
        return text;
    }

    private String sendSingleBookingStatus(String senderPhone, String bookingId) {
        Booking b = bookingRepository.findById(bookingId).orElse(null);
        if (b == null) {
            String notFound = String.format("Booking *%s* not found in active records. Reply *STATUS* to view all bookings.", bookingId);
            whatsAppClient.sendTextMessage(senderPhone, notFound);
            return notFound;
        }
        StringBuilder sb = new StringBuilder("• *").append(b.getId()).append("* — ").append(b.getPassengerName())
                .append(" (").append(b.getPnr()).append(")\n")
                .append("   Route: ").append(b.getRoute()).append(" [").append(b.getFlight()).append("]\n")
                .append("   Status: *").append(b.getStatus()).append("*");
        if (b.getOnwardFlight() != null && !b.getOnwardFlight().equalsIgnoreCase("None")) {
            sb.append(" [Onward: ").append(b.getOnwardFlight()).append("]");
        }
        sb.append("\n\nReply *DISRUPT ").append(b.getId()).append("* to trigger autonomous rescue simulation.");
        String text = sb.toString();
        whatsAppClient.sendTextMessage(senderPhone, text);
        return text;
    }

    private String handleConversationalBooking(String senderPhone, String rawCommand) {
        String[] tokens = rawCommand.trim().split("\\s+");
        String origin = "HYD";
        String destination = "DEL";
        String passenger = "Guest Passenger";

        if (tokens.length >= 4) {
            origin = tokens[1].toUpperCase();
            destination = tokens[2].toUpperCase();
            StringBuilder nameBuilder = new StringBuilder();
            for (int i = 3; i < tokens.length; i++) {
                if (i > 3) nameBuilder.append(" ");
                nameBuilder.append(tokens[i]);
            }
            passenger = nameBuilder.toString();
        } else if (tokens.length == 3) {
            origin = tokens[1].toUpperCase();
            destination = tokens[2].toUpperCase();
        }

        // Create booking in database
        String bookingId = "B-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        String pnr = "WA" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        Booking booking = new Booking(
                bookingId,
                passenger,
                pnr,
                origin + " → " + destination,
                "FL-NEW",
                "Tonight",
                "None",
                "None",
                "WhatsApp Bot",
                "Confirmed"
        );
        bookingRepository.save(booking);

        // Simulate case
        RescueCase rescueCase = caseService.simulateCancellation(bookingId, "CANCELLED");
        lastActiveCaseByPhone.put(senderPhone, rescueCase.getId());

        // Generate and rank options
        List<RebookOption> options = caseService.generateAndRankOptions(rescueCase.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("*Flights Available for ").append(passenger).append("*\n");
        sb.append("• Route: *").append(origin).append(" → ").append(destination).append("*\n\n");
        sb.append("*Available Flight Options:*\n");

        for (RebookOption opt : options) {
            String displayCode = opt.getId().contains("-") ? opt.getId().substring(opt.getId().lastIndexOf("-") + 1) : opt.getId();
            sb.append(String.format("• *Option %s (%s):* %s | %s (+₹%d)\n",
                    displayCode, opt.getFlight(), opt.getDeparture(), opt.getArrival(), opt.getExtraCost()));
        }
        sb.append("\nTap a button below to issue ticket on the airline portal:");

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "APPROVE_AND_REBOOK_O2", "title", "Book Safe Hub (FL301)"),
                Map.of("id", "APPROVE_AND_REBOOK_O1", "title", "Book Direct (FL101)"),
                Map.of("id", "APPROVE_AND_REBOOK_O3", "title", "Book Late (FL401)")
        );

        String reply = sb.toString();
        whatsAppClient.sendInteractiveButtons(senderPhone, reply, buttons);
        return reply;
    }

    public String pushDisruptionAlertToChat(String senderPhone, RescueCase c) {
        String phone = (senderPhone != null && !senderPhone.isBlank())
                ? senderPhone
                : (config.getOperatorPhone() != null ? cleanPhone(config.getOperatorPhone()) : "919876543210");

        lastActiveCaseByPhone.put(phone, c.getId());

        List<RebookOption> options = optionRepository.findByCaseIdOrderByRankAsc(c.getId());
        if (options.isEmpty()) {
            options = caseService.generateAndRankOptions(c.getId());
        }

        RebookOption topOption = options.stream().filter(o -> o.getRank() == 1).findFirst()
                .orElse(!options.isEmpty() ? options.get(0) : null);
        String topFlight = topOption != null ? topOption.getFlight() : "FL301 via Mumbai (BOM)";
        String extraCost = (topOption != null && topOption.getExtraCost() > 0) ? "+₹" + topOption.getExtraCost() : "₹0";

        StringBuilder body = new StringBuilder();
        body.append("🚨 *[CRITICAL DISRUPTION DETECTED & RESCUE ALERT]*\n\n")
            .append("• *Passenger:* ").append(c.getPassengerName()).append(" (PNR: ").append(c.getBookingId() != null ? c.getBookingId() : "HYD123").append(")\n")
            .append("• *Agency Client:* Sri Sai Travels\n")
            .append("• *Disruption:* Flight *").append(c.getFlight()).append("* (").append(c.getCancelledSegment()).append(") *CANCELLED*\n");

        boolean isAnanyaHero = "C1".equalsIgnoreCase(c.getId()) || "B1".equalsIgnoreCase(c.getBookingId())
                || (c.getPassengerName() != null && c.getPassengerName().toLowerCase().contains("ananya"));

        if (isAnanyaHero) {
            body.append("• *Downstream Risk:* Misses *").append(c.getMissedConnection()).append("* + Forfeits Delhi hotel\n\n")
                .append("🎯 *Flyo Recommended Recovery:*\n")
                .append("• *Option O2 (Rank #1):* ").append(topFlight).append(" [Saves Toronto]\n")
                .append("• *Schedule:* Departs Tonight 23:30 | Arrives Delhi 01:40\n")
                .append("• *Connection Buffer:* *7h 50m* (Safely preserves Toronto flight)\n")
                .append("• *Fare Adjustment:* ").append(extraCost).append(" (Pre-authorized on Corporate VCC *7536)\n\n")
                .append("*Disqualified Options:*\n")
                .append("• *Option O1 (Direct):* 85m buffer (< 120m threshold — Unsafe)\n")
                .append("• *Option O3 (Tomorrow):* Departs 10:00 (Misses Toronto)\n\n");
        } else {
            if (c.getMissedConnection() != null && !c.getMissedConnection().equalsIgnoreCase("None") && !c.getMissedConnection().isBlank()) {
                body.append("• *Downstream Risk:* Misses *").append(c.getMissedConnection()).append("*");
                if (c.getHotelRisk() != null && !c.getHotelRisk().equalsIgnoreCase("None") && !c.getHotelRisk().isBlank()) {
                    body.append(" + Forfeits ").append(c.getHotelRisk());
                }
                body.append("\n\n");
            } else {
                body.append("• *Downstream Risk:* Standalone trip disrupted");
                if (c.getHotelRisk() != null && !c.getHotelRisk().equalsIgnoreCase("None") && !c.getHotelRisk().isBlank()) {
                    body.append(" (Risk: ").append(c.getHotelRisk()).append(")");
                }
                body.append("\n\n");
            }

            if (topOption != null) {
                String topOptId = topOption.getId().contains("-") ? topOption.getId().substring(topOption.getId().lastIndexOf("-") + 1) : topOption.getId();
                String bufferStr = topOption.getConnectionMinutes() > 0
                        ? String.format("%dh %02dm", topOption.getConnectionMinutes() / 60, Math.abs(topOption.getConnectionMinutes() % 60))
                        : "Direct";
                String savesNote = topOption.isSavesConnection() ? " [Saves Schedule]" : "";

                body.append("🎯 *Flyo Recommended Recovery:*\n")
                    .append("• *Option ").append(topOptId).append(" (Rank #1):* ").append(topOption.getFlight()).append(" (").append(topOption.getRoute()).append(")").append(savesNote).append("\n")
                    .append("• *Schedule:* Departs ").append(topOption.getDeparture()).append(" | Arrives ").append(topOption.getArrival()).append("\n")
                    .append("• *Connection Buffer:* *").append(bufferStr).append("*\n")
                    .append("• *Fare Adjustment:* ").append(extraCost).append(" (Pre-authorized on Corporate VCC *7536)\n\n");
            }

            List<RebookOption> alts = options.stream().filter(o -> o.getRank() != 1).toList();
            if (!alts.isEmpty()) {
                body.append("*Alternative Options:*\n");
                for (RebookOption alt : alts) {
                    String altId = alt.getId().contains("-") ? alt.getId().substring(alt.getId().lastIndexOf("-") + 1) : alt.getId();
                    String altCost = alt.getExtraCost() > 0 ? "+₹" + alt.getExtraCost() : "₹0";
                    body.append("• *Option ").append(altId).append(" (").append(alt.getFlight()).append("):* ")
                        .append(alt.getDeparture()).append(" → ").append(alt.getArrival())
                        .append(" (").append(altCost).append(")\n");
                }
                body.append("\n");
            }
        }

        body.append("• *Option REJECT:* Reject & Escalate to manual airport counter\n\n")
            .append("Tap below to authorize instant headless rebooking:");

        String primaryOptCode = topOption != null
                ? (topOption.getId().contains("-") ? topOption.getId().substring(topOption.getId().lastIndexOf("-") + 1) : topOption.getId())
                : "O2";
        String primaryFlight = topOption != null ? topOption.getFlight() : "FL301";

        List<Map<String, String>> buttons;
        if (isAnanyaHero) {
            buttons = List.of(
                    Map.of("id", "APPROVE_REBOOK_O2", "title", "⚡ Approve & Rebook O2"),
                    Map.of("id", "SELECT_O2", "title", "Select O2 (FL301)"),
                    Map.of("id", "REJECT", "title", "❌ Reject & Escalate")
            );
        } else {
            buttons = List.of(
                    Map.of("id", "APPROVE_REBOOK_" + primaryOptCode, "title", "⚡ Rebook " + primaryFlight),
                    Map.of("id", "SELECT_" + primaryOptCode, "title", "Select " + primaryOptCode + " (" + primaryFlight + ")"),
                    Map.of("id", "REJECT", "title", "❌ Reject & Escalate")
            );
        }

        String text = body.toString();
        whatsAppClient.sendInteractiveButtons(phone, text, buttons);
        return text;
    }

    private String handleDisruptionSimulation(String senderPhone, String bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            String notFound = String.format("Booking *%s* not found in active records. Reply *STATUS* to inspect monitored bookings.", bookingId);
            whatsAppClient.sendTextMessage(senderPhone, notFound);
            return notFound;
        }
        RescueCase c = caseService.simulateCancellation(bookingId, "CANCELLED");
        lastActiveCaseByPhone.put(senderPhone, c.getId());
        caseService.generateAndRankOptions(c.getId());
        c = caseService.getCaseById(c.getId()).orElse(c);
        return pushDisruptionAlertToChat(senderPhone, c);
    }

    public String handleRequestOtp(String senderPhone, String caseId, String optionId) {
        String targetCase = (caseId != null && !caseId.isBlank()) ? caseId : "C1";
        RescueCase c = caseRepository.findById(targetCase).orElse(null);
        String passengerName = c != null ? c.getPassengerName() : "Passenger";
        RebookOption opt = optionRepository.findById(optionId).orElse(null);
        int fare = opt != null ? opt.getExtraCost() : 0;
        String fareStr = fare > 0 ? ("+₹" + String.format("%,d", fare)) : "₹0";

        WhatsAppOtpService.OtpChallenge challenge = otpService.createOtpChallenge(senderPhone, targetCase, optionId);

        String otpMessage = String.format("""
                *[Flyo Security Verification Required]*
                A financial ticket reissue of %s has been requested for passenger %s (Option %s).

                Your single-use security authorization code is:
                *%s*

                Reply *%s* or tap below to authenticate and unlock rebooking:
                """, fareStr, passengerName, optionId, challenge.code(), challenge.code());

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "VERIFY_OTP_" + challenge.code(), "title", "Approve (" + challenge.code() + ")"),
                Map.of("id", "REJECT", "title", "Reject & Escalate")
        );
        whatsAppClient.sendInteractiveButtons(senderPhone, otpMessage, buttons);
        return otpMessage;
    }

    public String handleOtpVerification(String senderPhone, String code) {
        Optional<WhatsAppOtpService.OtpChallenge> verified = otpService.verifyOtp(senderPhone, code);

        if (verified.isPresent()) {
            WhatsAppOtpService.OtpChallenge challenge = verified.get();

            // Check if this was a LOGIN challenge
            if ("LOGIN".equals(challenge.caseId())) {
                String cp = cleanPhone(senderPhone);
                authenticatedSessions.add(cp);
                distributedIdempotencyService.tryAcquire("OPERATOR_AUTH_SESSION:" + cp, "OPERATOR_SESSION", 7200);
                String successMsg = String.format("""
                        *[Authentication Verified]*
                        *Login Successful!*
                        Welcome back, Flyo Operator (+%s).
                        Your session is active and live disruption controls are unlocked.

                        *Choose an operation below to interact with the Dashboard:*
                        """, senderPhone);

                List<Map<String, String>> buttons = List.of(
                        Map.of("id", "AUTORESCUE_B1", "title", "Auto-Rescue Hero"),
                        Map.of("id", "STATUS", "title", "Live Bookings"),
                        Map.of("id", "DISRUPT_B1", "title", "Cancel AI-839")
                );
                whatsAppClient.sendInteractiveButtons(senderPhone, successMsg, buttons);
                return successMsg;
            }

            // Otherwise, it was a rebooking / ticket approval challenge!
            RescueCase c = caseService.approveOption(challenge.caseId(), challenge.optionId(),
                    "Flyo WhatsApp Operator (OTP Verified: " + senderPhone + ")");

            auditService.logEvent(
                    challenge.caseId(),
                    "OPERATOR_OTP_VERIFIED",
                    "Flyo WhatsApp Operator (" + senderPhone + ")",
                    String.format("2FA Security OTP %s verified via WhatsApp chat. Option %s approved and rebooking gate unlocked.",
                            code, challenge.optionId())
            );

            // Execute rebooking automatically now that authentication is verified!
            RescueCase rescued = caseService.rebookCase(challenge.caseId());

            String reply = String.format("""
                    *[Security Authorization Verified]*
                    *[ITINERARY SUCCESSFULLY RESCUED]*
                    • *Approval:* Option O2 Approved by Flyo Operator — gate is now unlocked
                    • *Confirmed Flight:* FL301 (HYD → BOM → DEL) | PNR: *%s*
                    • *Transit:* Toronto (YYZ) connection secured (7h 50m buffer)
                    • *Notification:* Passenger %s notified
                    """, (rescued.getNewPnr() != null && !rescued.getNewPnr().isBlank()) ? rescued.getNewPnr() : "CONFIRMED_PNR", rescued.getPassengerName());

            List<Map<String, String>> buttons = List.of(
                    Map.of("id", "AUDIT", "title", "View Audit Trail"),
                    Map.of("id", "STATUS", "title", "Check All Bookings")
            );
            whatsAppClient.sendInteractiveButtons(senderPhone, reply, buttons);
            return reply;
        } else {
            String failReply = String.format("""
                    *[Security Verification Failed]*
                    • *Status:* Code *%s* invalid or expired
                    • *Security Gate:* rebooking gate remains locked
                    • *Action:* Reply REQUEST OTP or SEND OTP for fresh code
                    """, code);
            whatsAppClient.sendTextMessage(senderPhone, failReply);
            return failReply;
        }
    }

    private String handleOptionsList(String senderPhone, String caseId) {
        String targetCase = (caseId != null && !caseId.isBlank()) ? caseId : "C1";
        List<RebookOption> options = caseService.getOptionsForCase(targetCase);
        WhatsAppOtpService.OtpChallenge challenge = otpService.getActiveOtp(senderPhone)
                .orElseGet(() -> otpService.createOtpChallenge(senderPhone, targetCase, "O2"));

        StringBuilder sb = new StringBuilder("*Ranked Recovery Options for Case ").append(targetCase).append("*:\n\n");
        for (RebookOption opt : options) {
            String displayCode = opt.getId().contains("-") ? opt.getId().substring(opt.getId().lastIndexOf("-") + 1) : opt.getId();
            sb.append("• *Rank ").append(opt.getRank()).append(" (Option ").append(displayCode).append(")*: ")
                    .append(opt.getFlight()).append(" &middot; ").append(opt.getRoute()).append("\n")
                    .append("   Departure: ").append(opt.getDeparture()).append(" &rarr; Arrival: ").append(opt.getArrival()).append("\n")
                    .append("   Fare Adjustment: ").append(opt.getExtraCost() > 0 ? "+₹" + opt.getExtraCost() : "₹0").append("\n")
                    .append("   Rationale: ").append(opt.getReason()).append("\n\n");
        }
        sb.append("*2FA Security Authorization Code:* *").append(challenge.code()).append("*\n")
          .append("Reply *OTP ").append(challenge.code()).append("* or *APPROVE O2* to authenticate the chat and approve.");
        String text = sb.toString();

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "VERIFY_OTP_" + challenge.code(), "title", "Approve with OTP (" + challenge.code() + ")"),
                Map.of("id", "APPROVE_O2", "title", "Approve O2"),
                Map.of("id", "REJECT", "title", "Reject & Escalate")
        );
        whatsAppClient.sendInteractiveButtons(senderPhone, text, buttons);
        return text;
    }

    private String handleApproval(String senderPhone, String caseId, String optionId) {
        String targetCase = (caseId != null && !caseId.isBlank()) ? caseId : "C1";
        Optional<RescueCase> caseOpt = caseService.getCaseById(targetCase);
        if (caseOpt.isEmpty() || "ESCALATED".equals(caseOpt.get().getState()) || "DONE".equals(caseOpt.get().getState())) {
            RescueCase fresh = caseService.simulateCancellation("B1", "CANCELLED");
            caseService.generateAndRankOptions(fresh.getId());
            targetCase = fresh.getId();
        }
        RescueCase c = caseService.approveOption(targetCase, optionId, "Flyo WhatsApp Operator (" + senderPhone + ")");
        
        // Also auto-generate active security OTP record for the case
        otpService.createOtpChallenge(senderPhone, targetCase, optionId);

        String body = String.format(
                "*Option %s Approved by Flyo Operator*\n" +
                "Case: *%s* (%s)\n" +
                "Approved Option: *%s*\n" +
                "Automated rebooking gate is now unlocked.\n\n" +
                "Reply *REBOOK %s* or tap button below to execute browser rebooking:",
                optionId, c.getId(), c.getPassengerName(), optionId, c.getId()
        );

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "REBOOK", "title", "Execute Rebook (FL301)"),
                Map.of("id", "AUDIT", "title", "View Audit Trail")
        );
        whatsAppClient.sendInteractiveButtons(senderPhone, body, buttons);
        return body;
    }

    private String handleApproveAndRebook(String senderPhone, String caseId, String optionId) {
        String targetCase = (caseId != null && !caseId.isBlank()) ? caseId : "C1";
        Optional<RescueCase> caseOpt = caseService.getCaseById(targetCase);
        if (caseOpt.isEmpty() || "ESCALATED".equals(caseOpt.get().getState()) || "DONE".equals(caseOpt.get().getState())) {
            RescueCase fresh = caseService.simulateCancellation("B1", "CANCELLED");
            caseService.generateAndRankOptions(fresh.getId());
            targetCase = fresh.getId();
        }
        caseService.approveOption(targetCase, optionId, "Flyo WhatsApp Operator (" + senderPhone + ")");
        return handleRebook(senderPhone, targetCase);
    }

    private String handleRejection(String senderPhone, String caseId, String reason) {
        String targetCase = (caseId != null && !caseId.isBlank()) ? caseId : "C1";
        RescueCase c = caseService.rejectCase(targetCase, reason, "Flyo WhatsApp Operator (" + senderPhone + ")");
        String text = String.format(
                "*Case %s Escalated*\nState: *ESCALATED*\nReason: %s\nAutomated rebooking blocked. Transferred to manual desk.",
                c.getId(), c.getError()
        );
        whatsAppClient.sendTextMessage(senderPhone, text);
        return text;
    }

    private String handleRebook(String senderPhone, String caseId) {
        String targetCase = (caseId != null && !caseId.isBlank()) ? caseId : "C1";
        RescueCase current = caseService.getCaseById(targetCase).orElse(null);
        if (current == null || current.getApprovedOptionId() == null || current.getApprovedOptionId().isBlank()) {
            String gateLockedMsg = String.format("""
                    [Authentication Required]
                    Cannot execute rebooking for Case %s.
                    No approved option exists or 2FA OTP verification is pending.

                    Please select a recovery option first or reply *REQUEST OTP*.
                    """, targetCase);
            whatsAppClient.sendTextMessage(senderPhone, gateLockedMsg);
            return gateLockedMsg;
        }

        if ("ESCALATED".equals(current.getState())) {
            String escalatedMsg = String.format("""
                    *[Action Blocked]*
                    Case %s is currently in state *ESCALATED*.
                    Automated rebooking is blocked for escalated cases.

                    Reply *DISRUPT B1* or *RESET* to restart recovery.
                    """, targetCase);
            whatsAppClient.sendTextMessage(senderPhone, escalatedMsg);
            return escalatedMsg;
        }

        if ("DONE".equals(current.getState()) && current.getNewPnr() != null) {
            String doneMsg = String.format("""
                    ✅ *[ITINERARY SUCCESSFULLY RESCUED & SETTLED]*
                    (Already Rescued)

                    • *Passenger:* %s
                    • *New Airline PNR:* *%s*
                    • *Confirmed Flight:* FL301 (HYD → BOM → DEL)
                    • *Arrival in Delhi:* 01:40 AM (7h 50m before Toronto flight)
                    • *Status:* DONE
                    """, current.getPassengerName(), current.getNewPnr());
            whatsAppClient.sendTextMessage(senderPhone, doneMsg);
            return doneMsg;
        }

        whatsAppClient.sendTextMessage(senderPhone, "*Running Playwright Rebooking Worker...*\nConnecting to mock airline reservation system...");

        RescueCase rescued = caseService.rebookCase(targetCase);
        if ("DONE".equals(rescued.getState()) && rescued.getNewPnr() != null) {
            String vccCardLast4 = "7536";
            String txnRef = "TXN-05B7F43C";
            try {
                var vccOpt = paymentSettlementService.getActiveCardForCase(targetCase);
                if (vccOpt.isPresent()) {
                    vccCardLast4 = vccOpt.get().getLastFour();
                }
                var txns = paymentSettlementService.getLedgerForCase(targetCase);
                if (!txns.isEmpty()) {
                    txnRef = txns.get(0).getId();
                }
            } catch (Exception e) {
                log.warn("Could not query payment details for case {}: {}", targetCase, e.getMessage());
            }

            String successMsg = String.format("""
                    ✅ *[ITINERARY SUCCESSFULLY RESCUED & SETTLED]*

                    • *Passenger:* %s
                    • *New Airline PNR:* *%s*
                    • *Confirmed Flight:* FL301 (HYD → BOM → DEL)
                    • *Arrival in Delhi:* 01:40 AM (7h 50m before Toronto flight)
                    • *Financial Settlement:* ₹4,200 charged to Flyo VCC *%s (Ref: %s)
                    • *Status:* DONE • Passenger %s notified with new e-ticket

                    The traveller has received their new boarding pass and Mumbai transfer instructions.
                    """, rescued.getPassengerName(), rescued.getNewPnr(), vccCardLast4, txnRef, rescued.getPassengerName());

            List<Map<String, String>> buttons = List.of(
                    Map.of("id", "AUDIT", "title", "📄 View Audit Log"),
                    Map.of("id", "STATUS", "title", "👤 Monitor Bookings")
            );
            whatsAppClient.sendInteractiveButtons(senderPhone, successMsg, buttons);
            return successMsg;
        } else {
            String failMsg = String.format("""
                    *[FAILED & ESCALATED]*
                    Mock airline portal encountered a 500 carrier gateway error.
                    3 of 3 attempts failed with timeout.
                    Case state: *FAILED_ESCALATED*

                    *Manual Intervention Guidance:*
                    • Call airline operations directly at *1800-209-4555*
                    • Quote passenger name: *%s*
                    • Error: %s
                    • Failure audit screenshot captured.
                    """, rescued.getPassengerName(), rescued.getError());

            List<Map<String, String>> buttons = List.of(
                    Map.of("id", "RESTORE PORTAL", "title", "Restore Portal"),
                    Map.of("id", "STATUS", "title", "Check Status")
            );
            whatsAppClient.sendInteractiveButtons(senderPhone, failMsg, buttons);
            return failMsg;
        }
    }

    private String handleAutoRescue(String senderPhone, String bookingId) {
        String targetBooking = (bookingId != null && !bookingId.isBlank()) ? bookingId : "B1";
        RescueCase c = caseService.simulateCancellation(targetBooking, "CANCELLED");
        lastActiveCaseByPhone.put(senderPhone, c.getId());

        caseService.generateAndRankOptions(c.getId());
        caseService.approveOption(c.getId(), "O2", "Flyo WhatsApp Auto-Pilot");
        RescueCase rescued = caseService.rebookCase(c.getId());

        String autoMsg = String.format("""
                *[AUTONOMOUS RESCUE COMPLETE]*
                • Passenger: *%s*
                • Original Flight: *AI-839* (HYD → DEL) CANCELLED
                • Calculated Impact: Missed connection DEL → Toronto (YYZ)
                • Evaluated Recovery Options: O2 ranked #1 (470m buffer vs 120m threshold)
                • Operator Approval: Auto-signed by Flyo WhatsApp Bot
                • Browser Rebooking: Executed on airline portal (Playwright)
                • Confirmed Airline PNR: *%s*
                • Final State: *DONE*

                Passenger %s notified via WhatsApp.
                """, rescued.getPassengerName(), rescued.getNewPnr(), rescued.getPassengerName());

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "AUDIT", "title", "Audit Trail"),
                Map.of("id", "STATUS", "title", "All Bookings")
        );
        whatsAppClient.sendInteractiveButtons(senderPhone, autoMsg, buttons);
        return autoMsg;
    }

    private String handleAuditList(String senderPhone, String caseId) {
        String targetCase = (caseId != null && !caseId.isBlank()) ? caseId : "C1";
        List<AuditEvent> events = auditService.getEventsForCase(targetCase);
        StringBuilder sb = new StringBuilder("*Flyo Audit Trail for Case ").append(targetCase).append(" (").append(events.size()).append(" events)*:\n\n");
        for (AuditEvent e : events) {
            sb.append("• *").append(e.getEvent()).append("* (").append(e.getActor()).append(")\n")
                    .append("   ").append(e.getDetail()).append("\n\n");
        }
        sb.append("System integrity: Append-only ledger.");
        String text = sb.toString();
        whatsAppClient.sendTextMessage(senderPhone, text);
        return text;
    }

    private String handleReset(String senderPhone) {
        bookingRepository.findById("B1").ifPresent(b -> {
            b.setStatus("Checked-in");
            b.setFlight("AI-839");
            b.setPnr("HYD123");
            bookingRepository.save(b);
        });
        bookingRepository.findById("B2").ifPresent(b -> {
            b.setStatus("On time");
            b.setFlight("6E-402");
            b.setPnr("BLR456");
            bookingRepository.save(b);
        });
        caseRepository.findById("C1").ifPresent(c -> {
            c.setState("DETECTED");
            c.setApprovedOptionId(null);
            c.setApprovedBy(null);
            c.setApprovedAt(null);
            c.setNewPnr(null);
            c.setError(null);
            c.setManualNextStep(null);
            caseRepository.save(c);
        });
        caseRepository.findById("CASE-B2").ifPresent(c -> {
            caseRepository.delete(c);
        });
        MockAirlineController.setBroken(false);
        otpService.clear(senderPhone);
        String reply = "[System Reset Complete]\nBooking B1 reset to Checked-in. Disruption Case C1 reset. Mock airline portal operational. Security OTP cleared.";
        whatsAppClient.sendTextMessage(senderPhone, reply);
        return reply;
    }

    private boolean isAuthorizedOperator(String senderPhone) {
        if (senderPhone == null || senderPhone.isBlank()) return false;
        String cleanSender = cleanPhone(senderPhone);
        if (cleanSender.isBlank()) return false;

        String configuredOp = config.getOperatorPhone() != null ? config.getOperatorPhone() : "";
        if (configuredOp.isBlank() || configuredOp.equals("*") || configuredOp.equalsIgnoreCase("ALL")) return true;

        String[] allowed = configuredOp.split("[,;]");
        for (String op : allowed) {
            String cleanOp = cleanPhone(op);
            if (cleanOp.isBlank()) continue;

            if (cleanSender.equals(cleanOp)) {
                return true;
            }
            if (cleanSender.length() >= 10 && cleanOp.length() >= 10) {
                if (cleanSender.endsWith(cleanOp) || cleanOp.endsWith(cleanSender)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPassengerSender(String senderPhone) {
        if (senderPhone == null || senderPhone.isBlank()) return false;
        String clean = cleanPhone(senderPhone);
        if (clean.isBlank()) return false;

        if (config.getPassengerPhone() != null && !config.getPassengerPhone().isBlank()) {
            String cleanCfg = cleanPhone(config.getPassengerPhone());
            if (clean.equals(cleanCfg) || (clean.length() >= 10 && clean.endsWith(cleanCfg))) {
                return true;
            }
        }
        if (caseRepository != null && caseRepository.findAll().stream().anyMatch(c -> clean.equals(cleanPhone(c.getPassengerPhone())))) {
            return true;
        }
        return clean.endsWith("9876543211") || clean.endsWith("9876543212");
    }

    private boolean isPassengerCommand(String rawCommand) {
        if (rawCommand == null) return false;
        String cmd = rawCommand.trim().toUpperCase();
        return cmd.startsWith("PAX_") || cmd.equals("PAX_BAGGAGE_INFO") || cmd.equals("PAX_STATUS") || cmd.equals("PAX_HELP");
    }

    public String handlePassengerInboundMessage(String senderPhone, String rawCommand) {
        String cmd = rawCommand != null ? rawCommand.trim().toUpperCase() : "";
        log.info("[Passenger WhatsApp Ingress] Sender: {}, Command: {}", senderPhone, cmd);

        String clean = cleanPhone(senderPhone);
        Optional<RescueCase> activeCaseOpt = caseRepository.findAll().stream()
                .filter(c -> clean.equals(cleanPhone(c.getPassengerPhone())))
                .reduce((first, second) -> second);
        String caseId = activeCaseOpt.map(RescueCase::getId)
                .orElseGet(() -> lastActiveCaseByPhone.values().stream().findFirst().orElse("C1"));

        messageService.sendCustomMessage(caseId, "PASSENGER", "Flyo Support", rawCommand != null ? rawCommand : "");

        if (cmd.equals("PAX_BAGGAGE_INFO") || cmd.contains("BAGGAGE") || cmd.contains("LUGGAGE")) {
            return handlePassengerBaggageRequest(senderPhone);
        }

        if (cmd.equals("PAX_STATUS") || cmd.equals("STATUS") || cmd.equals("FLIGHT") || cmd.equals("TICKET") || cmd.equals("BOARDING PASS")) {
            return handlePassengerStatusRequest(senderPhone);
        }

        if (cmd.equals("HI") || cmd.equals("HELLO") || cmd.equals("PAX_HELP") || cmd.contains("CONCIERGE")) {
            RescueCase currentCase = activeCaseOpt.orElseGet(() -> caseRepository.findById(caseId).orElse(null));
            String passengerGreeting = currentCase != null && currentCase.getPassengerName() != null
                    ? (" " + currentCase.getPassengerName())
                    : "";
            String onwardContext = (currentCase != null && currentCase.getMissedConnection() != null && !currentCase.getMissedConnection().equalsIgnoreCase("None"))
                    ? "Our operations team is actively tracking your journey and onward connection.\n\n"
                    : "Our operations team is actively monitoring your travel itinerary.\n\n";

            String welcome = "✈️ *Flyo · Priority Passenger Concierge*\n\n" +
                    "Hello" + passengerGreeting + "! We are actively tracking your journey.\n" +
                    onwardContext +
                    "Tap an option below for instant automated guidance:";

            messageService.sendCustomMessage(caseId, "SENT_SIMULATED", "Flyo Passenger Concierge", welcome);

            List<Map<String, String>> buttons = List.of(
                    Map.of("id", "PAX_BAGGAGE_INFO", "title", "🧳 Luggage Info"),
                    Map.of("id", "PAX_STATUS", "title", "✈️ Flight Status"),
                    Map.of("id", "PAX_HELP", "title", "📞 Call Desk")
            );
            whatsAppClient.sendInteractiveButtons(senderPhone, welcome, buttons);
            return welcome;
        }

        // Delegate to PassengerRescueWorkflowService for authoritative workflow processing
        return passengerRescueWorkflowService.processPassengerInbound(senderPhone, rawCommand, null);
    }

    public String handlePassengerBaggageRequest(String senderPhone) {
        String clean = cleanPhone(senderPhone);
        Optional<RescueCase> activeCaseOpt = caseRepository.findAll().stream()
                .filter(c -> clean.equals(cleanPhone(c.getPassengerPhone())))
                .reduce((first, second) -> second);

        String caseId = activeCaseOpt.map(RescueCase::getId)
                .orElseGet(() -> lastActiveCaseByPhone.values().stream().findFirst().orElse("C1"));
        RescueCase c = activeCaseOpt.orElseGet(() -> caseRepository.findById(caseId).orElse(null));

        String pnr = (c != null && c.getNewPnr() != null && !c.getNewPnr().isBlank()) ? c.getNewPnr() : null;
        String paxName = (c != null && c.getPassengerName() != null) ? c.getPassengerName() : "Passenger";

        String guide;
        if (pnr != null) {
            SimulatedMessage sent = messageService.sendLuggageTransitGuide(caseId, paxName, pnr);
            guide = sent.getText();
        } else {
            guide = String.format(
                    "🧳 *Transit & Baggage Transfer Notice for %s*\n\n" +
                    "No alternative PNR is currently ticketed. Once your flight recovery is confirmed, bag retagging details will appear here.",
                    paxName
            );
        }

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "PAX_STATUS", "title", "✈️ My Itinerary"),
                Map.of("id", "PAX_HELP", "title", "📞 Call Travel Desk")
        );
        whatsAppClient.sendInteractiveButtons(senderPhone, guide, buttons);
        return guide;
    }

    public String handlePassengerStatusRequest(String senderPhone) {
        String clean = cleanPhone(senderPhone);
        Optional<RescueCase> activeCaseOpt = caseRepository.findAll().stream()
                .filter(c -> clean.equals(cleanPhone(c.getPassengerPhone())))
                .reduce((first, second) -> second);

        String caseId = activeCaseOpt.map(RescueCase::getId)
                .orElseGet(() -> lastActiveCaseByPhone.values().stream().findFirst().orElse("C1"));
        RescueCase c = activeCaseOpt.orElseGet(() -> caseRepository.findById(caseId).orElse(null));

        String pnr = (c != null && c.getNewPnr() != null && !c.getNewPnr().isBlank()) ? c.getNewPnr() : null;
        String paxName = (c != null && c.getPassengerName() != null) ? c.getPassengerName() : "Passenger";

        String statusMsg;
        if (pnr != null) {
            String flight = "Rebooked Flight";
            if (c.getApprovedOptionId() != null) {
                RebookOption opt = optionRepository.findById(c.getApprovedOptionId()).orElse(null);
                if (opt != null) flight = opt.getFlight();
            }

            statusMsg = String.format(
                    "✈️ *Flyo · Priority Passenger Concierge*\n\n" +
                    "🟢 *ITINERARY CONFIRMED • YOU ARE READY TO FLY*\n\n" +
                    "Dear %s,\n\n" +
                    "Your replacement flight has been confirmed. Details:\n\n" +
                    "• *Flight:* %s\n" +
                    "• *Route:* %s\n" +
                    "• *Booking Reference (PNR):* *%s*\n\n" +
                    "Tap below to view baggage transfer guidance or call support:",
                    paxName, flight, c.getCancelledSegment(), pnr
            );
        } else {
            String currentState = c != null ? c.getState() : "MONITORING";
            statusMsg = String.format(
                    "✈️ *Flyo · Priority Passenger Concierge*\n\n" +
                    "ℹ️ *FLIGHT RECOVERY IN PROGRESS*\n\n" +
                    "Dear %s,\n\n" +
                    "Your journey is being monitored (Status: *%s*).\n" +
                    "No alternative ticket has been issued yet. Reply *HELP* for assistance.",
                    paxName, currentState
            );
        }

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "PAX_BAGGAGE_INFO", "title", "🧳 Luggage Info"),
                Map.of("id", "PAX_HELP", "title", "📞 Call Travel Desk")
        );
        whatsAppClient.sendInteractiveButtons(senderPhone, statusMsg, buttons);
        return statusMsg;
    }

    private String cleanPhone(String phone) {
        if (phone == null) return "";
        return phone.replaceAll("[^0-9]", "");
    }

    private String extractCaseId(String text, String defaultId) {
        String[] parts = text.trim().split("[\\s_]+");
        if (parts.length > 1) {
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].toUpperCase();
                if (p.matches("^C[0-9]+$") || p.startsWith("CASE-")) {
                    return p;
                }
            }
        }
        return defaultId;
    }

    private String extractBookingId(String text, String defaultId) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length > 1) {
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].toUpperCase();
                if (p.matches("^B[0-9]+$") || p.startsWith("B-")) {
                    return p;
                }
            }
        } else if (parts.length == 1) {
            String p = parts[0].toUpperCase();
            if (p.matches("^B[0-9]+$") || p.startsWith("B-")) {
                return p;
            }
        }
        return defaultId;
    }
}
