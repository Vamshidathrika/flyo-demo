package com.flyo.rescue.service;

import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Passenger-Facing WhatsApp Messaging Service.
 *
 * Implements authoritative interactive WhatsApp message templates and callbacks for passengers:
 * - Step 2: Proactive Flight Cancellation Notice with [RESCUE MY TRIP] & [ABANDON RESCUE]
 * - Step 3: Immediate Rescue Request Acknowledgment
 * - Step 6: Ranked Alternative Flight Options Display with selection buttons
 * - Step 7 & 8: Itinerary Preview & Confirmation OTP (Scenario A: Positive Fare, Scenario B: Zero Fare)
 * - Step 11: Verified Booking Confirmation & New PNR
 * - Step 12: Abandon Recovery Notice
 * - Exceptional: OTP Expired, Fare Changed, and Retry Guidance
 */
@Service
public class PassengerWhatsAppMessagingService {

    private static final Logger log = LoggerFactory.getLogger(PassengerWhatsAppMessagingService.class);

    private final MetaWhatsAppClient whatsAppClient;
    private final WhatsAppChatLogService chatLogService;
    private final MessageService messageService;

    public PassengerWhatsAppMessagingService(MetaWhatsAppClient whatsAppClient,
                                            WhatsAppChatLogService chatLogService,
                                            MessageService messageService) {
        this.whatsAppClient = whatsAppClient;
        this.chatLogService = chatLogService;
        this.messageService = messageService;
    }

    /**
     * Step 2: Sends initial Cancellation Notification to passenger with exactly two interactive actions.
     */
    public String sendCancellationNotification(String passengerPhone, Booking booking, RescueCase rescueCase) {
        String paxName = booking.getPassengerName();
        String flight = booking.getFlight();
        String route = booking.getRoute();
        String time = booking.getDateTime();

        String origin = "Origin";
        String destination = "Destination";
        if (route != null && (route.contains("→") || route.contains("->"))) {
            String[] parts = route.contains("→") ? route.split("→") : route.split("->");
            origin = parts[0].trim();
            destination = parts[1].trim();
        }

        String text = String.format("""
                Hello %s,

                We are sorry to inform you that your flight *%s* from %s to %s scheduled for %s has been cancelled.

                Don't worry. Flyo Rescue Agent can help you find alternative flights and recover your journey.

                What would you like to do?
                """, paxName, flight, origin, destination, time);

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "RESCUE_MY_TRIP_" + rescueCase.getId(), "title", "RESCUE MY TRIP"),
                Map.of("id", "ABANDON_RESCUE_" + rescueCase.getId(), "title", "ABANDON RESCUE")
        );

        whatsAppClient.sendInteractiveButtons(passengerPhone, text, buttons);
        if (rescueCase != null && rescueCase.getId() != null) {
            messageService.sendCustomMessage(rescueCase.getId(), "PASSENGER_WHATSAPP", passengerPhone, text);
        }
        log.info("[Passenger WhatsApp] Sent Step 2 Cancellation Notification to {}", passengerPhone);
        return text;
    }

    /**
     * Step 3: Sends immediate acknowledgment upon passenger tapping RESCUE MY TRIP.
     */
    public String sendRescueAcknowledgment(String passengerPhone, String caseId) {
        String text = """
                Your rescue request has been received.

                Flyo is reviewing your itinerary and searching for available alternative flights.

                We'll show you the best available options shortly.
                """;

        whatsAppClient.sendTextMessage(passengerPhone, text);
        if (caseId != null) {
            messageService.sendCustomMessage(caseId, "PASSENGER_WHATSAPP", passengerPhone, text);
        }
        log.info("[Passenger WhatsApp] Sent Step 3 Rescue Acknowledgment to {}", passengerPhone);
        return text;
    }

    /**
     * Step 6: Displays ranked alternative flights on WhatsApp with interactive selection buttons.
     */
    public String sendAlternativeFlightOptions(String passengerPhone, RescueCase rescueCase, List<RebookOption> options) {
        StringBuilder sb = new StringBuilder();
        sb.append("Good news, ").append(rescueCase.getPassengerName()).append("!\n\n")
          .append("We found alternative flights for your disrupted journey.\n\n")
          .append("Please review the available options and select the flight that works best for you:\n\n");

        List<Map<String, String>> buttons = new ArrayList<>();

        for (int i = 0; i < options.size(); i++) {
            RebookOption opt = options.get(i);
            String rankLabel = opt.getRank() == 1 ? "OPTION " + opt.getRank() + " — RECOMMENDED" : "OPTION " + opt.getRank();
            String fareDiff = opt.getExtraCost() > 0 ? "+₹" + String.format("%,d", opt.getExtraCost()) : "₹0";
            String bufferText = opt.getConnectionMinutes() > 0
                    ? String.format("%dh %02dm buffer", opt.getConnectionMinutes() / 60, Math.abs(opt.getConnectionMinutes() % 60))
                    : "Direct flight";

            sb.append("• *").append(rankLabel).append("*\n")
              .append("   Airline: ").append(opt.getFlight()).append("\n")
              .append("   Route: ").append(opt.getRoute()).append("\n")
              .append("   Departure: ").append(opt.getDeparture()).append("\n")
              .append("   Arrival: ").append(opt.getArrival()).append("\n")
              .append("   Connection Buffer: ").append(bufferText).append("\n")
              .append("   Fare Difference: ").append(fareDiff).append("\n\n");

            if (buttons.size() < 2) {
                String optCode = opt.getId().contains("-") ? opt.getId().substring(opt.getId().lastIndexOf("-") + 1) : opt.getId();
                buttons.add(Map.of(
                        "id", "SELECT_OPTION_" + rescueCase.getId() + "_" + optCode,
                        "title", "Select " + opt.getFlight() + " (" + fareDiff + ")"
                ));
            }
        }

        buttons.add(Map.of("id", "ABANDON_RESCUE_" + rescueCase.getId(), "title", "ABANDON RESCUE"));

        String text = sb.toString();
        whatsAppClient.sendInteractiveButtons(passengerPhone, text, buttons);
        if (rescueCase != null && rescueCase.getId() != null) {
            messageService.sendCustomMessage(rescueCase.getId(), "PASSENGER_WHATSAPP", passengerPhone, text);
        }
        log.info("[Passenger WhatsApp] Sent Step 6 Alternative Flight Options to {}", passengerPhone);
        return text;
    }

    /**
     * Step 7 & 8: Sends final itinerary preview and requests passenger confirmation OTP.
     * Supports Scenario A (positive fare) and Scenario B (zero fare).
     */
    public String sendItineraryPreviewAndOtpRequest(String passengerPhone, RescueCase rescueCase,
                                                    RebookOption selectedOption, String otpCode) {
        int fare = selectedOption.getExtraCost();
        String currency = "₹";
        String fareText = fare > 0 ? (currency + String.format("%,d", fare)) : "₹0";

        String connectionStatus = (rescueCase.getMissedConnection() != null && !rescueCase.getMissedConnection().equalsIgnoreCase("None"))
                ? "Protected (" + rescueCase.getMissedConnection() + ")"
                : "Direct flight (No onward transfers)";

        StringBuilder sb = new StringBuilder();
        sb.append("Please review your selected alternative flight.\n\n")
          .append("• Selected Flight: *").append(selectedOption.getFlight()).append("*\n")
          .append("• Route: ").append(selectedOption.getRoute()).append("\n")
          .append("• Departure: ").append(selectedOption.getDeparture()).append("\n")
          .append("• Arrival: ").append(selectedOption.getArrival()).append("\n")
          .append("• Additional Fare: *").append(fareText).append("*\n")
          .append("• Onward connection: ").append(connectionStatus).append("\n\n");

        if (fare > 0) {
            // Scenario A: Positive Fare Difference
            sb.append("Your selected flight requires an additional payment of *").append(fareText).append("*.\n\n")
              .append("To confirm your selected flight and authorize Flyo to proceed with the approved recovery process, enter the verification code sent to your verified contact.\n\n");
        } else {
            // Scenario B: Zero Fare Difference
            sb.append("Your selected alternative flight requires no additional payment.\n\n")
              .append("To confirm your selection and proceed with rebooking, please enter your verification code.\n\n");
        }

        sb.append("Your single-use confirmation code is:\n*").append(otpCode).append("*\n\n")
          .append("Reply *").append(otpCode).append("* to confirm and authorize rebooking.");

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "VERIFY_PASSENGER_OTP_" + rescueCase.getId() + "_" + otpCode, "title", "Confirm (" + otpCode + ")"),
                Map.of("id", "ABANDON_RESCUE_" + rescueCase.getId(), "title", "ABANDON RESCUE")
        );

        String text = sb.toString();
        whatsAppClient.sendInteractiveButtons(passengerPhone, text, buttons);
        if (rescueCase != null && rescueCase.getId() != null) {
            messageService.sendCustomMessage(rescueCase.getId(), "PASSENGER_WHATSAPP", passengerPhone, text);
        }
        log.info("[Passenger WhatsApp] Sent Step 7/8 Itinerary Preview & OTP to {}", passengerPhone);
        return text;
    }

    /**
     * Step 11: Sends authoritative Booking Confirmation message to passenger.
     */
    public String sendBookingConfirmation(String passengerPhone, RescueCase rescueCase,
                                          RebookOption bookedOption, String verifiedPnr,
                                          String vccRef) {
        int fare = bookedOption != null ? bookedOption.getExtraCost() : 0;
        String fareText = fare > 0 ? ("₹" + String.format("%,d", fare) + " (Corporate payment settled)") : "₹0 (Complimentary recovery — no additional charge)";
        String flight = bookedOption != null ? bookedOption.getFlight() : "FL301";
        String route = bookedOption != null ? bookedOption.getRoute() : rescueCase.getCancelledSegment();
        String dep = bookedOption != null ? bookedOption.getDeparture() : "Confirmed Schedule";
        String arr = bookedOption != null ? bookedOption.getArrival() : "Confirmed Schedule";

        String text = String.format("""
                ✈️ *Your flight rescue is confirmed!*

                Your alternative flight has been successfully booked and ticketed.

                • Passenger: *%s*
                • New Flight: *%s*
                • Route: %s
                • Departure: %s
                • Arrival: %s
                • Airline PNR: *%s*
                • E-Ticket Status: *ISSUED & VALIDATED*
                • E-Ticket / Itinerary: http://localhost:8080/mock-airline/itinerary?pnr=%s
                • Additional Payment: *%s*
                • Baggage Guidance: Checked luggage automatically transfers. Show this confirmed PNR at check-in / gate.
                • Boarding Pass: Available upon carrier online check-in (opens 24h prior to departure).

                Thank you for choosing Flyo.
                """,
                rescueCase.getPassengerName(),
                flight,
                route,
                dep,
                arr,
                verifiedPnr,
                verifiedPnr,
                fareText
        );

        List<Map<String, String>> buttons = List.of(
                Map.of("id", "STATUS", "title", "Check Status"),
                Map.of("id", "AUDIT", "title", "View Audit Trail")
        );

        whatsAppClient.sendInteractiveButtons(passengerPhone, text, buttons);
        if (rescueCase != null && rescueCase.getId() != null) {
            messageService.sendCustomMessage(rescueCase.getId(), "PASSENGER_WHATSAPP", passengerPhone, text);
        }
        log.info("[Passenger WhatsApp] Sent Step 11 Booking Confirmation with PNR {} to {}", verifiedPnr, passengerPhone);
        return text;
    }

    /**
     * Step 12: Sends Abandon Confirmation when passenger declines recovery.
     */
    public String sendAbandonConfirmation(String passengerPhone) {
        String text = """
                You have chosen not to proceed with Flyo's alternative flight recovery.

                Your rescue request has been closed.

                Please contact your travel agency for further assistance.
                """;

        whatsAppClient.sendTextMessage(passengerPhone, text);
        log.info("[Passenger WhatsApp] Sent Step 12 Abandon Confirmation to {}", passengerPhone);
        return text;
    }

    /**
     * Sends OTP Expired or Invalid Notice.
     */
    public String sendOtpFailedNotice(String passengerPhone, String reason) {
        String msg;
        if ("EXPIRED".equalsIgnoreCase(reason)) {
            msg = """
                    *[Verification Code Expired]*
                    Your confirmation code has expired for your security.

                    Reply *RESCUE* or tap below to receive a fresh verification code.
                    """;
        } else if ("LOCKED_OUT".equalsIgnoreCase(reason)) {
            msg = """
                    *[Security Notice — Too Many Attempts]*
                    Multiple invalid verification codes entered.
                    For your protection, confirmation is temporarily locked for 15 minutes.
                    Please contact our priority desk at +91 98765 43210.
                    """;
        } else {
            msg = """
                    *[Invalid Verification Code]*
                    The code entered does not match our records.
                    Please re-check your 6-digit verification code and reply again.
                    """;
        }

        whatsAppClient.sendTextMessage(passengerPhone, msg);
        return msg;
    }

    /**
     * Sends Fare Changed Notice when airline fare fluctuates before confirmation.
     */
    public String sendFareChangedNotice(String passengerPhone, int oldFare, int newFare) {
        String msg = String.format("""
                *[Fare Update Notice]*
                The airline reservation system reported a schedule/fare adjustment before confirmation:
                • Previous additional fare: ₹%s
                • Updated additional fare: ₹%s

                Please review the updated fare and confirm your selection.
                """, String.format("%,d", oldFare), String.format("%,d", newFare));

        whatsAppClient.sendTextMessage(passengerPhone, msg);
        return msg;
    }
}
