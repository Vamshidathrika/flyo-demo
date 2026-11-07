package com.flyo.rescue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.integration.ProviderCapabilityRegistry;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.ProviderCategory;
import com.flyo.rescue.integration.ProviderMetadata;
import com.flyo.rescue.integration.reconciliation.ExternalStateReconciliationService;
import com.flyo.rescue.integration.webhook.WebhookEventProcessor;
import com.flyo.rescue.model.DiscrepancyRecord;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.DiscrepancyRecordRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.repository.WebhookEventRepository;
import com.flyo.rescue.security.DistributedRateLimiter;
import com.flyo.rescue.security.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class ProviderIntegrationAndTenantTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProviderCapabilityRegistry providerRegistry;

    @Autowired
    private DistributedRateLimiter rateLimiter;

    @Autowired
    private WebhookEventProcessor webhookProcessor;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private ExternalStateReconciliationService reconciliationService;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private com.flyo.rescue.repository.BookingRepository bookingRepository;

    @Autowired
    private DiscrepancyRecordRepository discrepancyRecordRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String webhookSecret = "flyo-enterprise-webhook-signing-secret-prod-2026";

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        webhookEventRepository.deleteAll();
        discrepancyRecordRepository.deleteAll();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        TenantContext.clear();
        discrepancyRecordRepository.deleteAll();
        webhookEventRepository.deleteAll();
        rescueCaseRepository.findAll().stream()
                .filter(c -> c.getId() != null && c.getId().startsWith("CASE-RECON-"))
                .forEach(c -> {
                    String bId = c.getBookingId();
                    rescueCaseRepository.delete(c);
                    if (bId != null && bId.startsWith("B-RECON-")) {
                        bookingRepository.deleteById(bId);
                    }
                });
    }

    private String calculateHmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(hmacBytes);
    }

    @Test
    @DisplayName("Stage 2: Provider Capability Registry lists verified, unverified, and sandbox adapters")
    void testProviderCapabilityRegistry() {
        List<ProviderMetadata> providers = providerRegistry.getAllProviders();
        assertFalse(providers.isEmpty(), "Registry should contain initialized provider adapters");

        // Configured Not Verified adapters
        assertTrue(providers.stream().anyMatch(p -> "aviationstack".equals(p.getProviderId())
                && p.getCapabilityStatus() == ProviderCapabilityStatus.CONFIGURED_NOT_VERIFIED));
        assertTrue(providers.stream().anyMatch(p -> "amadeus_altea".equals(p.getProviderId())
                && p.getCapabilityStatus() == ProviderCapabilityStatus.CONFIGURED_NOT_VERIFIED));

        // Sandbox Contract Tested adapters
        assertTrue(providers.stream().anyMatch(p -> "flyo_sandbox_payment_auth".equals(p.getProviderId())
                && p.getCapabilityStatus() == ProviderCapabilityStatus.SANDBOX_CONTRACT_TESTED));
        assertTrue(providers.stream().anyMatch(p -> "stripe_issuing".equals(p.getProviderId())
                && p.getCapabilityStatus() == ProviderCapabilityStatus.SANDBOX_CONTRACT_TESTED));

        // Sandbox Connected adapters
        assertTrue(providers.stream().anyMatch(p -> "playwright_mock_airline".equals(p.getProviderId())
                && p.getCapabilityStatus() == ProviderCapabilityStatus.SANDBOX_CONNECTED));

        // Verify status transition
        providerRegistry.updateCapabilityStatus("stripe_issuing", ProviderCapabilityStatus.DISABLED);
        assertEquals(ProviderCapabilityStatus.DISABLED,
                providerRegistry.getProvider("stripe_issuing").orElseThrow().getCapabilityStatus());
        // Reset back
        providerRegistry.updateCapabilityStatus("stripe_issuing", ProviderCapabilityStatus.SANDBOX_CONTRACT_TESTED);
    }

    @Test
    @DisplayName("Stage 2: Distributed Rate Limiter blocks excessive requests in sliding window")
    void testDistributedRateLimiter() {
        String testKey = "rate_limit_test_" + UUID.randomUUID();

        // Allow 3 requests in a 10-second window
        assertTrue(rateLimiter.tryAcquire(testKey, 3, 10));
        assertTrue(rateLimiter.tryAcquire(testKey, 3, 10));
        assertTrue(rateLimiter.tryAcquire(testKey, 3, 10));

        // 4th request must be rejected
        assertFalse(rateLimiter.tryAcquire(testKey, 3, 10), "4th request must exceed rate limit");

        // Reset clears bucket
        rateLimiter.reset(testKey);
        assertTrue(rateLimiter.tryAcquire(testKey, 3, 10), "Should allow request after reset");
    }

    @Test
    @DisplayName("Stage 2: Ingest webhook with valid HMAC signature and timestamp")
    void testWebhookIngestionValidHmac() throws Exception {
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        String payload = "{\"eventType\":\"FLIGHT_STATUS_UPDATE\",\"flightNumber\":\"AI-839\",\"status\":\"CANCELLED\"}";
        long timestamp = Instant.now().getEpochSecond();
        String signature = calculateHmac(payload, webhookSecret);

        mockMvc.perform(post("/api/webhooks/incoming")
                .header("X-Flyo-Event-ID", eventId)
                .header("X-Flyo-Signature", signature)
                .header("X-Flyo-Timestamp", timestamp)
                .header("X-Tenant-ID", "tenant_corporate_01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SUCCESS")))
                .andExpect(jsonPath("$.code", is("PROCESSED")));

        assertTrue(webhookEventRepository.existsByEventId(eventId));
    }

    @Test
    @DisplayName("Stage 2: Webhook with forged HMAC signature is rejected 401 Unauthorized")
    void testWebhookIngestionInvalidHmac() throws Exception {
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        String payload = "{\"eventType\":\"FLIGHT_STATUS_UPDATE\",\"flightNumber\":\"AI-839\",\"status\":\"CANCELLED\"}";
        long timestamp = Instant.now().getEpochSecond();
        String forgedSignature = "sha256=0000000000000000000000000000000000000000000000000000000000000000";

        mockMvc.perform(post("/api/webhooks/incoming")
                .header("X-Flyo-Event-ID", eventId)
                .header("X-Flyo-Signature", forgedSignature)
                .header("X-Flyo-Timestamp", timestamp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("INVALID_SIGNATURE")));

        assertFalse(webhookEventRepository.existsByEventId(eventId));
    }

    @Test
    @DisplayName("Stage 2: Webhook replay protection rejects timestamp older than 300 seconds")
    void testWebhookReplayProtection() throws Exception {
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        String payload = "{\"eventType\":\"FLIGHT_STATUS_UPDATE\"}";
        long oldTimestamp = Instant.now().getEpochSecond() - 360; // 6 minutes ago
        String signature = calculateHmac(payload, webhookSecret);

        mockMvc.perform(post("/api/webhooks/incoming")
                .header("X-Flyo-Event-ID", eventId)
                .header("X-Flyo-Signature", signature)
                .header("X-Flyo-Timestamp", oldTimestamp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("EXPIRED_TIMESTAMP")));

        assertFalse(webhookEventRepository.existsByEventId(eventId));
    }

    @Test
    @DisplayName("Stage 2: Duplicate webhook submission is deduplicated idempotently")
    void testWebhookIdempotentDeduplication() throws Exception {
        String eventId = "evt_dedup_" + UUID.randomUUID().toString().replace("-", "");
        String payload = "{\"eventType\":\"PAYMENT_SETTLED\",\"amount\":4200}";
        long timestamp = Instant.now().getEpochSecond();
        String signature = calculateHmac(payload, webhookSecret);

        // First delivery
        mockMvc.perform(post("/api/webhooks/incoming")
                .header("X-Flyo-Event-ID", eventId)
                .header("X-Flyo-Signature", signature)
                .header("X-Flyo-Timestamp", timestamp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("PROCESSED")));

        // Duplicate delivery with exact same event ID
        mockMvc.perform(post("/api/webhooks/incoming")
                .header("X-Flyo-Event-ID", eventId)
                .header("X-Flyo-Signature", signature)
                .header("X-Flyo-Timestamp", timestamp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("IDEMPOTENT_DUPLICATE")));

        // Must still only have 1 record in database
        assertEquals(1, webhookEventRepository.count());
    }

    @Test
    @DisplayName("Stage 2: External state reconciliation identifies local DONE vs remote CANCELLED divergence")
    void testExternalStateReconciliation() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 6);
        String uniqueBookingId = "B-RECON-" + uniqueSuffix;
        com.flyo.rescue.model.Booking testBooking = new com.flyo.rescue.model.Booking(
                uniqueBookingId, "Sanjay Patel", "RECON" + uniqueSuffix,
                "HYD-BOM", "6E-501", "Tonight 20:00", "None", "None", "Flyo", "Confirmed"
        );
        bookingRepository.save(testBooking);

        String testCaseId = "CASE-RECON-" + uniqueSuffix;
        RescueCase c = new RescueCase(
                testCaseId, uniqueBookingId, "Sanjay Patel", "DONE",
                "HYD-BOM", "6E-501", "Disrupted", "None", "None"
        );
        c.setNewPnr("REMOTE-OK-1");
        c.setTenantId("tenant_corporate_tmc");
        rescueCaseRepository.save(c);

        // Remote state reports CANCELLED and ticket VOID
        List<DiscrepancyRecord> discrepancies = reconciliationService.reconcileCaseState(testCaseId, "CANCELLED", "VOID");
        assertFalse(discrepancies.isEmpty(), "Must generate discrepancy record");
        assertEquals("REMOTE_PNR_VOID_OR_CANCELLED", discrepancies.get(0).getDiscrepancyType());

        // Resolve discrepancy
        String discId = discrepancies.get(0).getId();
        reconciliationService.resolveDiscrepancy(discId, "Contacted carrier operations; ticket refunded manually.");

        List<DiscrepancyRecord> unresolved = reconciliationService.getUnresolvedDiscrepancies("tenant_corporate_tmc");
        assertTrue(unresolved.stream().noneMatch(d -> d.getId().equals(discId)),
                "Resolved discrepancy must not appear in unresolved list");
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("Stage 2: Admin can query providers and update capability status")
    void testAdminProviderEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(5))))
                .andExpect(jsonPath("$[?(@.providerId == 'aviationstack')].category", hasItem("FLIGHT_STATUS")));

        mockMvc.perform(put("/api/admin/providers/sabre_gds/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedStatus", is("DISABLED")));

        // Reset
        mockMvc.perform(put("/api/admin/providers/sabre_gds/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"CONFIGURED_NOT_VERIFIED\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "auditor", roles = {"AUDITOR"})
    @DisplayName("Stage 2: Auditor without supervisor/admin role is forbidden from provider management")
    void testAuditorForbiddenFromAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/providers"))
                .andExpect(status().isForbidden());
    }
}
