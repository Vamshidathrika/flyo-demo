package com.flyo.rescue.service;

import com.flyo.rescue.model.SimulatedMessage;
import com.flyo.rescue.repository.SimulatedMessageRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageService {

    private final SimulatedMessageRepository messageRepository;

    public MessageService(SimulatedMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public List<SimulatedMessage> getMessagesForCase(String caseId) {
        return messageRepository.findByCaseIdOrderByTimestampAsc(caseId);
    }

    public SimulatedMessage saveDraft(String caseId, String passengerName, String cancelledFlight, String recommendedFlight) {
        // Remove existing draft if any
        List<SimulatedMessage> existing = messageRepository.findByCaseIdOrderByTimestampAsc(caseId);
        for (SimulatedMessage m : existing) {
            if ("DRAFT".equals(m.getType())) {
                messageRepository.delete(m);
            }
        }

        String recipient = passengerName + " (+91 98765 43210)";
        String text = String.format(
                "*FLYO FLIGHT DISRUPTION NOTICE*\n" +
                "• *Disruption:* Flight %s (HYD → DEL) cancelled by carrier\n" +
                "• *Action:* Flyo Operations is rebooking you on Flight %s\n" +
                "• *Toronto Connection:* Preserved with 7h 50m transit buffer\n" +
                "• *Next Step:* Dispatching confirmed ticket upon operator 2FA approval.",
                cancelledFlight, recommendedFlight
        );

        SimulatedMessage message = new SimulatedMessage(caseId, "DRAFT", recipient, text);
        return messageRepository.save(message);
    }

    public SimulatedMessage sendSimulatedSuccessMessage(String caseId, String passengerName, String flight, String newPnr) {
        String recipient = passengerName + " (+91 98765 43210)";
        String text = String.format(
                "*FLYO REBOOKING CONFIRMATION*\n" +
                "• *Confirmed Flight:* %s (HYD → BOM → DEL)\n" +
                "• *Schedule:* Departs 23:30 tonight | Arrives Delhi 01:40\n" +
                "• *Airline PNR:* *%s*\n" +
                "• *Toronto Connection:* 7h 50m safe buffer for YYZ 09:30 flight\n" +
                "• *Baggage:* Checked luggage auto-transfers in Mumbai",
                flight, newPnr
        );

        SimulatedMessage message = new SimulatedMessage(caseId, "SENT_SIMULATED", recipient, text);
        return messageRepository.save(message);
    }

    public SimulatedMessage sendPassengerReassuranceNotice(String caseId, String passengerName, String cancelledFlight, String recommendedFlight, String onwardFlight) {
        String recipient = (passengerName != null ? passengerName : "Passenger") + " (+91 98765 43210)";
        String onwardDesc = (onwardFlight != null && !onwardFlight.equalsIgnoreCase("None")) ? onwardFlight : "Toronto (YYZ)";
        String text = String.format(
                "✈️ *Sri Sai Travels · Priority Travel Desk*\n\n" +
                "Dear %s,\n\n" +
                "We have proactively detected that your flight *%s* from Hyderabad to Delhi tonight has been cancelled by the airline due to airport congestion.\n\n" +
                "⚠️ *Please do not worry about your %s flight.*\n\n" +
                "Our priority operations system is already securing an alternative connection for you on *%s via Mumbai*, which will get you to Delhi at *01:40 AM* — leaving a comfortable *7 hours and 50 minutes* before your %s flight departs.\n\n" +
                "⏳ *What you should do right now:*\n" +
                "1. Continue to Rajiv Gandhi International Airport (HYD) as normal.\n" +
                "2. *Do not stand in the airline customer service line.*\n" +
                "3. Your updated confirmed e-ticket will arrive directly in this WhatsApp chat in approximately 60 seconds.\n\n" +
                "Warm regards,\n" +
                "*Ravi Kumar* · Sri Sai Travels Travel Desk\n" +
                "Emergency Contact: +91 98765 43210",
                passengerName != null ? passengerName : "Valued Traveller",
                cancelledFlight != null ? cancelledFlight : "AI-839",
                onwardDesc,
                recommendedFlight != null ? recommendedFlight : "FL301",
                onwardDesc
        );

        SimulatedMessage message = new SimulatedMessage(caseId, "PASSENGER_STAGE_1_REASSURANCE", recipient, text);
        return messageRepository.save(message);
    }

    public SimulatedMessage sendPassengerConfirmedTicket(String caseId, String passengerName, String flight, String newPnr, String route, String departure, String arrival) {
        String recipient = (passengerName != null ? passengerName : "Passenger") + " (+91 98765 43210)";
        String text = String.format(
                "🟢 *ITINERARY CONFIRMED • YOU ARE READY TO FLY*\n\n" +
                "Dear %s,\n\n" +
                "Your replacement flight has been confirmed and ticketed! Your international connection to Toronto is 100%% secured.\n\n" +
                "📋 *Your New Flight Details:*\n" +
                "• *Flight:* %s · StarSky Airlines\n" +
                "• *Routing:* %s\n" +
                "• *Departure:* %s\n" +
                "• *Delhi Arrival:* %s\n" +
                "• *New Airline PNR:* *%s*\n\n" +
                "Toronto Connection: 7h 50m buffer before your 09:30 AM departure on AC-043.\n\n" +
                "Tap below to access your e-boarding pass or view luggage transfer details:\n" +
                "1. [ 🎫 Open Boarding Pass ] (http://localhost:8080/mock-airline/confirm.html?pnr=%s)\n" +
                "2. [ 🧳 Luggage Transfer Info ] (Reply PAX_BAGGAGE_INFO)\n" +
                "3. [ 📞 Call Travel Desk ] (+91 98765 43210)",
                passengerName != null ? passengerName : "Valued Traveller",
                flight != null ? flight : "FL301",
                route != null ? route : "Hyderabad (HYD) → Mumbai (BOM) → Delhi (DEL)",
                departure != null ? departure : "Tonight at 23:30 (Terminal 1)",
                arrival != null ? arrival : "Tomorrow at 01:40 AM (Terminal 3)",
                newPnr != null ? newPnr : "R7X2K9",
                newPnr != null ? newPnr : "R7X2K9"
        );

        SimulatedMessage message = new SimulatedMessage(caseId, "PASSENGER_STAGE_2_TICKET", recipient, text);
        return messageRepository.save(message);
    }

    public SimulatedMessage sendLuggageTransitGuide(String caseId, String passengerName, String newPnr) {
        String recipient = (passengerName != null ? passengerName : "Passenger") + " (+91 98765 43210)";
        String text = String.format(
                "🧳 *Transit & Baggage Transfer Guide for %s*\n\n" +
                "Here is how your transit works tonight:\n\n" +
                "1. *Check-in at Hyderabad (HYD):*\n" +
                "   Show PNR *%s* at the StarSky check-in counter. Your bags will be **through-checked directly to Delhi (DEL)**.\n\n" +
                "2. *Mumbai (BOM) 45-Minute Transit:*\n" +
                "   You will stay airside at Terminal 2. Follow the purple \"Domestic Transfers\" signs to Gate 42. You do *not* need to collect your luggage.\n\n" +
                "3. *Delhi (DEL) Transfer to International:*\n" +
                "   You arrive at Terminal 3 at 01:40 AM. Your Toronto flight (AC-043) departs from the same terminal (T3) at 09:30 AM.\n\n" +
                "Need anything else? Reply directly to this chat and your dedicated travel manager will assist you immediately.",
                passengerName != null ? passengerName : "Valued Traveller",
                newPnr != null ? newPnr : "R7X2K9"
        );

        SimulatedMessage message = new SimulatedMessage(caseId, "PASSENGER_STAGE_3_BAGGAGE", recipient, text);
        return messageRepository.save(message);
    }

    public SimulatedMessage sendCustomMessage(String caseId, String type, String recipient, String text) {
        SimulatedMessage message = new SimulatedMessage(caseId != null ? caseId : "C1", type, recipient, text);
        return messageRepository.save(message);
    }

    public void clearMessagesForCase(String caseId) {
        List<SimulatedMessage> messages = messageRepository.findByCaseIdOrderByTimestampAsc(caseId);
        messageRepository.deleteAll(messages);
    }
}
