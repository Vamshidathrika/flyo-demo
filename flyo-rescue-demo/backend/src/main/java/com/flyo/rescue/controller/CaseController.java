package com.flyo.rescue.controller;

import com.flyo.rescue.config.WhatsAppConfig;
import com.flyo.rescue.dto.ApproveRequest;
import com.flyo.rescue.dto.RejectRequest;
import com.flyo.rescue.model.AuditEvent;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.SimulatedMessage;
import com.flyo.rescue.service.AuditService;
import com.flyo.rescue.service.CaseService;
import com.flyo.rescue.service.MessageService;
import com.flyo.rescue.service.WhatsAppCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cases")
@CrossOrigin(origins = "*")
public class CaseController {

    private static final Logger log = LoggerFactory.getLogger(CaseController.class);

    private final CaseService caseService;
    private final AuditService auditService;
    private final MessageService messageService;
    private final WhatsAppCommandService commandService;
    private final WhatsAppConfig whatsAppConfig;

    public CaseController(CaseService caseService,
                          AuditService auditService,
                          MessageService messageService,
                          WhatsAppCommandService commandService,
                          WhatsAppConfig whatsAppConfig) {
        this.caseService = caseService;
        this.auditService = auditService;
        this.messageService = messageService;
        this.commandService = commandService;
        this.whatsAppConfig = whatsAppConfig;
    }

    @PostMapping("/simulate")
    public ResponseEntity<RescueCase> simulateCase(
            @RequestParam(defaultValue = "B1") String bookingId,
            @RequestParam(defaultValue = "CANCELLED") String type) {
        RescueCase rescueCase = caseService.simulateCancellation(bookingId, type);
        return ResponseEntity.ok(rescueCase);
    }

    @GetMapping
    public ResponseEntity<List<RescueCase>> getAllCases() {
        return ResponseEntity.ok(caseService.getAllCases());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RescueCase> getCaseById(@PathVariable String id) {
        return caseService.getCaseById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/options")
    public ResponseEntity<List<RebookOption>> getCaseOptions(@PathVariable String id) {
        return ResponseEntity.ok(caseService.getOptionsForCase(id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<RescueCase> approveCase(
            @PathVariable String id,
            @RequestBody ApproveRequest request) {
        RescueCase updated = caseService.approveOption(id, request.getOptionId(), request.getApprovedBy());
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<RescueCase> rejectCase(
            @PathVariable String id,
            @RequestBody(required = false) RejectRequest request) {
        String reason = request != null ? request.getReason() : "Rejected by Flyo Operator";
        String rejectedBy = request != null ? request.getRejectedBy() : "Flyo Operator";
        RescueCase updated = caseService.rejectCase(id, reason, rejectedBy);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/rebook")
    public ResponseEntity<RescueCase> rebookCase(@PathVariable String id) {
        RescueCase updated = caseService.rebookCase(id);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{id}/audit")
    public ResponseEntity<List<AuditEvent>> getCaseAudit(@PathVariable String id) {
        return ResponseEntity.ok(auditService.getEventsForCase(id));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<SimulatedMessage>> getCaseMessages(@PathVariable String id) {
        return ResponseEntity.ok(messageService.getMessagesForCase(id));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<SimulatedMessage> sendPassengerMessage(
            @PathVariable String id,
            @RequestBody(required = false) java.util.Map<String, String> payload) {
        String text = payload != null ? payload.get("text") : "";
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String sender = payload.getOrDefault("sender", "PASSENGER");
        SimulatedMessage userMsg = messageService.sendCustomMessage(id, sender, "Flyo Support", text);

        String lower = text.toLowerCase();
        if (lower.contains("luggage") || lower.contains("baggage") || lower.contains("bag") || lower.contains("transit") || lower.contains("pax_baggage_info")) {
            RescueCase c = caseService.getCaseById(id).orElse(null);
            String pnr = (c != null && c.getNewPnr() != null) ? c.getNewPnr() : "R7X2K9";
            String paxName = (c != null && c.getPassengerName() != null) ? c.getPassengerName() : "Ananya Sharma";
            messageService.sendLuggageTransitGuide(id, paxName, pnr);
            return ResponseEntity.ok(userMsg);
        } else if (lower.contains("status") || lower.contains("ticket") || lower.contains("boarding") || lower.contains("pax_status")) {
            RescueCase c = caseService.getCaseById(id).orElse(null);
            String pnr = (c != null && c.getNewPnr() != null) ? c.getNewPnr() : "R7X2K9";
            String paxName = (c != null && c.getPassengerName() != null) ? c.getPassengerName() : "Ananya Sharma";
            messageService.sendPassengerConfirmedTicket(
                    id,
                    paxName,
                    "FL301",
                    pnr,
                    "Hyderabad (HYD) → Mumbai (BOM) → Delhi (DEL)",
                    "Tonight at 23:30 (Terminal 1)",
                    "Tomorrow at 01:40 AM (Terminal 3)"
            );
            return ResponseEntity.ok(userMsg);
        }

        String replyText;
        if (lower.contains("hotel")) {
            replyText = "*HOTEL RESERVATION UPDATE*\n• *Property:* Delhi Airport Transit Hotel\n• *Check-in:* Adjusted for late arrival (01:40)\n• *Status:* Confirmed and guaranteed";
        } else if (lower.contains("toronto") || lower.contains("connection")) {
            replyText = "*TRANSIT CONNECTION TIMELINE*\n• *Delhi Arrival:* FL301 touches down at 01:40\n• *Toronto Departure:* Leaves on schedule at 09:30\n• *Safe Buffer:* 7 hours 50 minutes";
        } else if (lower.contains("thank") || lower.contains("thanks")) {
            replyText = "*FLYO PASSENGER SUPPORT*\n• *Status:* You are welcome, Ananya\n• *Monitoring:* Operations team is tracking your transit";
        } else {
            replyText = "✈️ *Sri Sai Travels · Priority Passenger Concierge*\n\nHello Ananya! Operations is tracking your journey.\nReply *BAGGAGE* for transfer instructions or *STATUS* for your updated e-ticket.";
        }
        messageService.sendCustomMessage(id, "SENT_SIMULATED", "Ananya Sharma (+91 98765 43210)", replyText);

        return ResponseEntity.ok(userMsg);
    }
}
