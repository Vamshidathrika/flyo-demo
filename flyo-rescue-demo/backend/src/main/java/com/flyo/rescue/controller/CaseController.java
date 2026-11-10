package com.flyo.rescue.controller;

import com.flyo.rescue.config.WhatsAppConfig;
import com.flyo.rescue.dto.ApproveRequest;
import com.flyo.rescue.dto.RejectRequest;
import com.flyo.rescue.model.AuditEvent;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.SimulatedMessage;
import com.flyo.rescue.service.AuditService;
import com.flyo.rescue.service.CancellationNotificationService;
import com.flyo.rescue.service.CaseService;
import com.flyo.rescue.service.MessageService;
import com.flyo.rescue.service.WhatsAppCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cases")
@CrossOrigin(origins = "*")
public class CaseController {

    private static final Logger log = LoggerFactory.getLogger(CaseController.class);

    private final CaseService caseService;
    private final CancellationNotificationService cancellationNotificationService;
    private final AuditService auditService;
    private final MessageService messageService;
    private final WhatsAppCommandService commandService;
    private final WhatsAppConfig whatsAppConfig;

    public CaseController(CaseService caseService,
                          CancellationNotificationService cancellationNotificationService,
                          AuditService auditService,
                          MessageService messageService,
                          WhatsAppCommandService commandService,
                          WhatsAppConfig whatsAppConfig) {
        this.caseService = caseService;
        this.cancellationNotificationService = cancellationNotificationService;
        this.auditService = auditService;
        this.messageService = messageService;
        this.commandService = commandService;
        this.whatsAppConfig = whatsAppConfig;
    }

    @PostMapping("/simulate")
    public ResponseEntity<RescueCase> simulateCase(
            @RequestParam(defaultValue = "B1") String bookingId,
            @RequestParam(defaultValue = "CANCELLED") String type,
            @RequestParam(defaultValue = "false") boolean notifyPassenger) {
        if (notifyPassenger) {
            RescueCase rescueCase = cancellationNotificationService.handleFlightCancellation(bookingId, type, "Operations Console");
            return ResponseEntity.ok(rescueCase);
        }
        RescueCase rescueCase = caseService.simulateCancellation(bookingId, type);
        return ResponseEntity.ok(rescueCase);
    }

    @PostMapping("/disrupt")
    public ResponseEntity<RescueCase> disruptAndNotify(
            @RequestParam(defaultValue = "B1") String bookingId,
            @RequestParam(defaultValue = "CANCELLED") String type,
            @RequestParam(defaultValue = "Operations Console") String source) {
        RescueCase rescueCase = cancellationNotificationService.handleFlightCancellation(bookingId, type, source);
        return ResponseEntity.ok(rescueCase);
    }

    @GetMapping
    public ResponseEntity<List<RescueCase>> getAllCases() {
        return ResponseEntity.ok(caseService.getAllCases());
    }

    @GetMapping("/operations-summary")
    public ResponseEntity<Map<String, Object>> getOperationsSummary() {
        List<RescueCase> allCases = caseService.getAllCases();
        long activeCount = allCases.stream().filter(c ->
                List.of("NEW", "ANALYZING", "OPTIONS_READY", "APPROVED", "REBOOKING", "VERIFYING_BOOKING", "VERIFYING_TICKET")
                        .contains(c.getState())).count();
        long manualCount = allCases.stream().filter(c -> "MANUAL_INTERVENTION_REQUIRED".equals(c.getState())).count();
        long failedCount = allCases.stream().filter(c -> "FAILED_ESCALATED".equals(c.getState())).count();
        long doneCount = allCases.stream().filter(c -> "DONE".equals(c.getState())).count();

        List<RescueCase> attentionRequired = allCases.stream()
                .filter(c -> "MANUAL_INTERVENTION_REQUIRED".equals(c.getState()) || "FAILED_ESCALATED".equals(c.getState()))
                .toList();

        Map<String, Object> summary = new HashMap<>();
        summary.put("tenantId", com.flyo.rescue.security.TenantContext.getTenantId());
        summary.put("totalCases", allCases.size());
        summary.put("activeDisruptionsCount", activeCount);
        summary.put("manualInterventionCount", manualCount);
        summary.put("failedEscalationsCount", failedCount);
        summary.put("resolvedDoneCount", doneCount);
        summary.put("attentionRequiredCases", attentionRequired);
        return ResponseEntity.ok(summary);
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

    @PostMapping("/{id}/reconcile")
    public ResponseEntity<RescueCase> reconcileCase(
            @PathVariable String id,
            @RequestParam(defaultValue = "Flyo Operator") String operator) {
        RescueCase reconciled = caseService.reconcileCase(id, operator);
        return ResponseEntity.ok(reconciled);
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
        RescueCase c = caseService.getCaseById(id).orElse(null);
        String pnr = (c != null && c.getNewPnr() != null && !c.getNewPnr().isBlank()) ? c.getNewPnr() : "PENDING_CONFIRMATION";
        String paxName = (c != null && c.getPassengerName() != null) ? c.getPassengerName() : "Passenger";
        String paxPhone = (c != null && c.getPassengerPhone() != null) ? c.getPassengerPhone() : "+91 98765 43210";
        String route = (c != null && c.getCancelledSegment() != null) ? c.getCancelledSegment() : "Disrupted Flight Route";

        if (lower.contains("luggage") || lower.contains("baggage") || lower.contains("bag") || lower.contains("transit") || lower.contains("pax_baggage_info")) {
            messageService.sendLuggageTransitGuide(id, paxName, pnr);
            return ResponseEntity.ok(userMsg);
        } else if (lower.contains("status") || lower.contains("ticket") || lower.contains("boarding") || lower.contains("pax_status")) {
            messageService.sendPassengerConfirmedTicket(
                    id,
                    paxName,
                    (c != null && c.getFlight() != null) ? c.getFlight() : "REBOOK_PENDING",
                    pnr,
                    route,
                    "Confirmed Schedule",
                    "Confirmed Arrival"
            );
            return ResponseEntity.ok(userMsg);
        }

        String replyText;
        if (lower.contains("hotel")) {
            String hotelInfo = (c != null && c.getHotelRisk() != null && !c.getHotelRisk().equalsIgnoreCase("None"))
                    ? c.getHotelRisk()
                    : "Transit Accommodations";
            replyText = "*HOTEL RESERVATION UPDATE*\n• *Property:* " + hotelInfo + "\n• *Check-in:* Adjusted for late arrival\n• *Status:* Confirmed and guaranteed";
        } else if (lower.contains("toronto") || lower.contains("connection")) {
            String connInfo = (c != null && c.getMissedConnection() != null && !c.getMissedConnection().equalsIgnoreCase("None"))
                    ? c.getMissedConnection()
                    : "Direct transit protected";
            replyText = "*TRANSIT CONNECTION TIMELINE*\n• *Connection:* " + connInfo + "\n• *Safe Buffer:* Adequate recovery buffer maintained";
        } else if (lower.contains("thank") || lower.contains("thanks")) {
            replyText = "*FLYO PASSENGER SUPPORT*\n• *Status:* You are welcome, " + paxName + "\n• *Monitoring:* Operations team is tracking your transit";
        } else {
            replyText = "✈️ *Flyo · Priority Passenger Concierge*\n\nHello " + paxName + "! Operations is tracking your journey.\nReply *BAGGAGE* for transfer instructions or *STATUS* for your updated e-ticket.";
        }
        messageService.sendCustomMessage(id, "SENT_SIMULATED", paxName + " (" + paxPhone + ")", replyText);

        return ResponseEntity.ok(userMsg);
    }
}
