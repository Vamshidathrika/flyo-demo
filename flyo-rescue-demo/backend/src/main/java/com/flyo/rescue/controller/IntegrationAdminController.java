package com.flyo.rescue.controller;

import com.flyo.rescue.integration.ProviderCapabilityRegistry;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.ProviderMetadata;
import com.flyo.rescue.integration.reconciliation.ExternalStateReconciliationService;
import com.flyo.rescue.integration.webhook.WebhookEventProcessor;
import com.flyo.rescue.model.DiscrepancyRecord;
import com.flyo.rescue.model.WebhookEvent;
import com.flyo.rescue.repository.WebhookEventRepository;
import com.flyo.rescue.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Enterprise Integration Administration and Webhook Ingestion Controller.
 * Manages provider capability states, webhook event ingestion, and state reconciliation controls.
 */
@RestController
@CrossOrigin
public class IntegrationAdminController {

    private final ProviderCapabilityRegistry providerRegistry;
    private final WebhookEventProcessor webhookProcessor;
    private final WebhookEventRepository webhookEventRepository;
    private final ExternalStateReconciliationService reconciliationService;

    public IntegrationAdminController(ProviderCapabilityRegistry providerRegistry,
                                      WebhookEventProcessor webhookProcessor,
                                      WebhookEventRepository webhookEventRepository,
                                      ExternalStateReconciliationService reconciliationService) {
        this.providerRegistry = providerRegistry;
        this.webhookProcessor = webhookProcessor;
        this.webhookEventRepository = webhookEventRepository;
        this.reconciliationService = reconciliationService;
    }

    // =========================================================================
    // 1. PROVIDER CAPABILITY REGISTRY CONTROLS
    // =========================================================================

    @GetMapping("/api/admin/providers")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<List<ProviderMetadata>> getAllProviders() {
        return ResponseEntity.ok(providerRegistry.getAllProviders());
    }

    @GetMapping("/api/admin/providers/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<?> getProviderById(@PathVariable String id) {
        return providerRegistry.getProvider(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/api/admin/providers/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateProviderStatus(@PathVariable String id,
                                                  @RequestBody Map<String, String> body) {
        String statusStr = body.get("status");
        if (statusStr == null || statusStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'status' field"));
        }

        try {
            ProviderCapabilityStatus newStatus = ProviderCapabilityStatus.valueOf(statusStr.toUpperCase());
            providerRegistry.updateCapabilityStatus(id, newStatus);
            return ResponseEntity.ok(Map.of(
                    "providerId", id,
                    "updatedStatus", newStatus.name(),
                    "message", "Provider capability status updated successfully"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid capability status or provider ID: " + e.getMessage()));
        }
    }

    // =========================================================================
    // 2. AUTHENTICATED WEBHOOK INGESTION
    // =========================================================================

    @PostMapping("/api/webhooks/incoming")
    public ResponseEntity<?> receiveWebhook(
            @RequestHeader(value = "X-Flyo-Event-ID", required = true) String eventId,
            @RequestHeader(value = "X-Flyo-Signature", required = true) String signature,
            @RequestHeader(value = "X-Flyo-Timestamp", required = true) long timestamp,
            @RequestBody String rawPayload) {

        WebhookEventProcessor.WebhookProcessingResult result = webhookProcessor.processIncomingWebhook(
                eventId, rawPayload, signature, timestamp
        );

        if (result.isSuccess()) {
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "eventId", result.getEventId(),
                    "code", result.getCode(),
                    "message", result.getMessage()
            ));
        } else {
            HttpStatus status = "INVALID_SIGNATURE".equals(result.getCode())
                    ? HttpStatus.UNAUTHORIZED
                    : HttpStatus.BAD_REQUEST;

            return ResponseEntity.status(status).body(Map.of(
                    "status", "REJECTED",
                    "eventId", result.getEventId(),
                    "code", result.getCode(),
                    "message", result.getMessage()
            ));
        }
    }

    @GetMapping("/api/admin/webhooks")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<List<WebhookEvent>> getTenantWebhooks() {
        String tenantId = TenantContext.getTenantId();
        return ResponseEntity.ok(webhookEventRepository.findByTenantIdOrderByCreatedAtDesc(tenantId));
    }

    // =========================================================================
    // 3. EXTERNAL STATE RECONCILIATION CONTROLS
    // =========================================================================

    @GetMapping("/api/admin/reconciliations/discrepancies")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<List<DiscrepancyRecord>> getUnresolvedDiscrepancies() {
        String tenantId = TenantContext.getTenantId();
        return ResponseEntity.ok(reconciliationService.getUnresolvedDiscrepancies(tenantId));
    }

    @PostMapping("/api/admin/reconciliations/{id}/resolve")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<?> resolveDiscrepancy(@PathVariable String id,
                                                @RequestBody Map<String, String> body) {
        String notes = body.getOrDefault("notes", "Manually reconciled by supervisor");
        reconciliationService.resolveDiscrepancy(id, notes);
        return ResponseEntity.ok(Map.of(
                "discrepancyId", id,
                "status", "RESOLVED",
                "notes", notes
        ));
    }
}
