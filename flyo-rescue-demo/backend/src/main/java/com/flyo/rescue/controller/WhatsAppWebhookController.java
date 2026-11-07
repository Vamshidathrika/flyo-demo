package com.flyo.rescue.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.config.WhatsAppConfig;
import com.flyo.rescue.service.MetaSignatureVerifier;
import com.flyo.rescue.service.WhatsAppCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final WhatsAppConfig config;
    private final MetaSignatureVerifier signatureVerifier;
    private final WhatsAppCommandService commandService;
    private final com.flyo.rescue.service.WhatsAppChatLogService chatLogService;
    private final com.flyo.rescue.service.WhatsAppOtpService otpService;
    private final com.flyo.rescue.service.PassengerRescueWorkflowService passengerRescueWorkflowService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Idempotency: Process each Meta message ID at most once
    private final Set<String> processedMessageIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public WhatsAppWebhookController(WhatsAppConfig config,
                                     MetaSignatureVerifier signatureVerifier,
                                     WhatsAppCommandService commandService,
                                     com.flyo.rescue.service.WhatsAppChatLogService chatLogService,
                                     com.flyo.rescue.service.WhatsAppOtpService otpService,
                                     com.flyo.rescue.service.PassengerRescueWorkflowService passengerRescueWorkflowService) {
        this.config = config;
        this.signatureVerifier = signatureVerifier;
        this.commandService = commandService;
        this.chatLogService = chatLogService;
        this.otpService = otpService;
        this.passengerRescueWorkflowService = passengerRescueWorkflowService;
    }

    /**
     * Operator WhatsApp Chat Stream (GET)
     * Returns chronological inbound and outbound WhatsApp conversation for UI sync.
     */
    @GetMapping(value = "/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.List<com.flyo.rescue.service.WhatsAppChatLogService.ChatMessage>> getChatMessages() {
        return ResponseEntity.ok(chatLogService.getChatHistory());
    }

    /**
     * Clear Operator WhatsApp Chat History (POST)
     */
    @PostMapping(value = "/clear")
    public ResponseEntity<String> clearChatMessages() {
        chatLogService.clear();
        return ResponseEntity.ok("CLEARED");
    }

    /**
     * Request Security OTP via WhatsApp Chat (POST)
     * Generates a single-use 6-digit OTP and delivers it directly to the operator WhatsApp chat.
     */
    @PostMapping(value = "/otp/request", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.Map<String, Object>> requestOtp(
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "caseId", required = false, defaultValue = "C1") String caseId,
            @RequestParam(value = "optionId", required = false, defaultValue = "O2") String optionId) {
        String targetPhone = phone != null && !phone.isBlank() ? phone : config.getOperatorPhone();
        String reply = commandService.handleRequestOtp(targetPhone, caseId, optionId);
        return ResponseEntity.ok(java.util.Map.of(
                "success", true,
                "phone", targetPhone,
                "message", "Security OTP dispatched via WhatsApp chat"
        ));
    }

    /**
     * Verify Security OTP (POST)
     * Validates OTP submitted by operator via UI validator or chat and unlocks rebooking gate.
     */
    @PostMapping(value = "/otp/verify", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.Map<String, Object>> verifyOtp(@RequestBody java.util.Map<String, String> request) {
        String phone = request.getOrDefault("phone", config.getOperatorPhone());
        String code = request.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("success", false, "error", "OTP code required"));
        }

        String reply = commandService.handleOtpVerification(phone, code);
        boolean success = reply.contains("Security Authorization Verified");
        return ResponseEntity.ok(java.util.Map.of(
                "success", success,
                "phone", phone,
                "reply", reply
        ));
    }

    /**
     * Get Active OTP Status (GET)
     */
    @GetMapping(value = "/otp/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.Map<String, Object>> getOtpStatus(
            @RequestParam(value = "phone", required = false) String phone) {
        String targetPhone = phone != null && !phone.isBlank() ? phone : config.getOperatorPhone();
        java.util.Optional<com.flyo.rescue.service.WhatsAppOtpService.OtpChallenge> opt = otpService.getActiveOtp(targetPhone);
        if (opt.isPresent()) {
            com.flyo.rescue.service.WhatsAppOtpService.OtpChallenge ch = opt.get();
            return ResponseEntity.ok(java.util.Map.of(
                    "active", true,
                    "phone", ch.phone(),
                    "caseId", ch.caseId(),
                    "optionId", ch.optionId(),
                    "expiresAt", ch.expiresAt().toString()
            ));
        }
        return ResponseEntity.ok(java.util.Map.of("active", false));
    }

    /**
     * Simulated Inbound WhatsApp Command (POST)
     * Allows UI console and test suites to simulate operator chat input directly.
     */
    @PostMapping(value = "/simulate-incoming", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.Map<String, Object>> simulateIncomingCommand(@RequestBody java.util.Map<String, String> request) {
        String sender = request.getOrDefault("sender", config.getOperatorPhone());
        String message = request.get("message");
        String buttonId = request.get("buttonId");

        try {
            String reply;
            if (passengerRescueWorkflowService.isPassengerMessage(sender, message, buttonId)) {
                chatLogService.logInbound(sender, message != null ? message : (buttonId != null ? "[" + buttonId + "]" : ""));
                reply = passengerRescueWorkflowService.processPassengerInbound(sender, message, buttonId);
            } else {
                reply = commandService.processInboundMessage(sender, message, buttonId);
            }
            return ResponseEntity.ok(java.util.Map.of(
                    "success", true,
                    "sender", sender,
                    "reply", reply != null ? reply : ""
            ));
        } catch (Exception e) {
            log.warn("[WhatsApp Webhook] Handled error in incoming command: {}", e.getMessage());
            String errorReply = "⚠️ *[Action Notice]*: " + e.getMessage();
            chatLogService.logOutbound(sender, errorReply, java.util.List.of());
            return ResponseEntity.ok(java.util.Map.of(
                    "success", false,
                    "sender", sender,
                    "reply", errorReply
            ));
        }
    }

    /**
     * Meta Webhook Verification Endpoint (GET)
     * Meta sends: ?hub.mode=subscribe&hub.challenge=1158201444&hub.verify_token=meatytoken
     */
    @GetMapping(value = "/webhook", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(value = "hub.mode", required = false) String mode,
            @RequestParam(value = "hub.challenge", required = false) String challenge,
            @RequestParam(value = "hub.verify_token", required = false) String verifyToken) {

        log.info("[Meta Webhook Verification] Mode: {}, Token: {}, Challenge: {}", mode, maskToken(verifyToken), challenge);

        if ("subscribe".equals(mode) && config.getVerifyToken().equals(verifyToken)) {
            log.info("[Meta Webhook Verification] Successfully verified Meta challenge token!");
            return ResponseEntity.ok(challenge);
        }

        log.warn("[Meta Webhook Verification] Failed verify token check. Mode: {}, Token: {}", mode, maskToken(verifyToken));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed: invalid token");
    }

    private String maskToken(String token) {
        if (token == null || token.isBlank()) return "null";
        if (token.length() <= 4) return "****";
        return token.substring(0, 2) + "****" + token.substring(token.length() - 2);
    }

    /**
     * Inbound WhatsApp Message Ingress (POST)
     * Validates X-Hub-Signature-256 and routes messages to WhatsAppCommandService
     */
    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receiveWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestBody String rawPayload) {

        // 1. Signature Security Validation
        if (config.getAppSecret() != null && !config.getAppSecret().isBlank() && !config.getAppSecret().startsWith("mock_")) {
            boolean validSignature = signatureVerifier.verifySignature(rawPayload, signatureHeader, config.getAppSecret());
            if (!validSignature) {
                log.warn("[Meta Webhook Security] Invalid HMAC-SHA256 signature rejected!");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature");
            }
        }

        // 2. Parse Meta WhatsApp Payload
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode entryArray = root.path("entry");
            if (entryArray.isArray()) {
                for (JsonNode entry : entryArray) {
                    JsonNode changesArray = entry.path("changes");
                    if (changesArray.isArray()) {
                        for (JsonNode change : changesArray) {
                            JsonNode value = change.path("value");

                            // Process message delivery status receipts (sent, delivered, read, failed)
                            JsonNode statuses = value.path("statuses");
                            if (statuses.isArray()) {
                                for (JsonNode statusNode : statuses) {
                                    String statusId = statusNode.path("id").asText();
                                    String status = statusNode.path("status").asText();
                                    String recipientId = statusNode.path("recipient_id").asText();
                                    log.info("[Meta Webhook Status] Message {} status update: {} for recipient {}", statusId, status, recipientId);
                                    if ("failed".equalsIgnoreCase(status)) {
                                        JsonNode errors = statusNode.path("errors");
                                        log.warn("[Meta Webhook Status] Outbound message delivery failed for {}: {}", statusId, errors);
                                    }
                                }
                            }

                            JsonNode messages = value.path("messages");
                            if (messages.isArray()) {
                                for (JsonNode msg : messages) {
                                    String msgId = msg.path("id").asText();
                                    String from = msg.path("from").asText();
                                    String type = msg.path("type").asText();

                                    // Deduplicate by message ID
                                    if (msgId != null && !msgId.isBlank()) {
                                        if (!processedMessageIds.add(msgId)) {
                                            log.info("[Meta Webhook] Duplicate message ID skipped: {}", msgId);
                                            continue;
                                        }
                                    }

                                    String textBody = null;
                                    String buttonId = null;

                                    if ("text".equals(type)) {
                                        textBody = msg.path("text").path("body").asText();
                                    } else if ("interactive".equals(type)) {
                                        JsonNode interactive = msg.path("interactive");
                                        buttonId = interactive.path("button_reply").path("id").asText();
                                        if (buttonId.isBlank()) {
                                            buttonId = interactive.path("list_reply").path("id").asText();
                                        }
                                    }

                                    // Dispatch to passenger workflow or operator command processor
                                    if (passengerRescueWorkflowService.isPassengerMessage(from, textBody, buttonId)) {
                                        chatLogService.logInbound(from, textBody != null ? textBody : (buttonId != null ? "[" + buttonId + "]" : ""));
                                        passengerRescueWorkflowService.processPassengerInbound(from, textBody, buttonId);
                                    } else {
                                        commandService.processInboundMessage(from, textBody, buttonId);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return ResponseEntity.ok("EVENT_RECEIVED");
        } catch (Exception e) {
            log.error("[Meta Webhook] Error processing incoming payload: {}", e.getMessage(), e);
            return ResponseEntity.ok("EVENT_RECEIVED"); // Meta requires 200 OK to avoid retry loops
        }
    }
}
