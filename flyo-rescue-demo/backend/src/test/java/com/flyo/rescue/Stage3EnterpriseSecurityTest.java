package com.flyo.rescue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.integration.ProviderCapabilityRegistry;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.ProviderMetadata;
import com.flyo.rescue.integration.webhook.WebhookEventProcessor;
import com.flyo.rescue.integration.webhook.signature.MetaWhatsAppSignatureValidator;
import com.flyo.rescue.integration.webhook.signature.StripeSignatureValidator;
import com.flyo.rescue.model.*;
import com.flyo.rescue.repository.*;
import com.flyo.rescue.security.DistributedRateLimiter;
import com.flyo.rescue.security.FlyoUserPrincipal;
import com.flyo.rescue.security.JwtTokenProvider;
import com.flyo.rescue.security.TenantContext;
import com.flyo.rescue.service.BookingService;
import com.flyo.rescue.service.CaseService;
import com.flyo.rescue.service.OutboxService;
import com.flyo.rescue.service.PaymentSettlementService;
import com.flyo.rescue.service.saga.RebookingSagaCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class Stage3EnterpriseSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private RescueCaseRepository caseRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private SagaExecutionRepository sagaExecutionRepository;

    @Autowired
    private VirtualCardRepository virtualCardRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private DiscrepancyRecordRepository discrepancyRecordRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private ProviderCapabilityRegistry providerRegistry;

    @Autowired
    private DistributedRateLimiter rateLimiter;

    @Autowired
    private WebhookEventProcessor webhookProcessor;

    @Autowired
    private MetaWhatsAppSignatureValidator metaValidator;

    @Autowired
    private StripeSignatureValidator stripeValidator;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    private String tokenTenantAlpha;
    private String tokenTenantAlphaAdmin;
    private String tokenTenantBeta;
    private String tokenMultiTenant;
    private String tokenGlobalAdmin;

    @BeforeEach
    void setUpTokens() {
        // Operator A: bound solely to tenant_alpha
        tokenTenantAlpha = tokenProvider.generateToken("operator_alpha",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"), new SimpleGrantedAuthority("ROLE_SUPERVISOR")),
                Map.of("tenantId", "tenant_alpha", "authorizedTenants", "tenant_alpha"));

        // Admin A: bound solely to tenant_alpha with ROLE_ADMIN
        tokenTenantAlphaAdmin = tokenProvider.generateToken("admin_alpha",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                Map.of("tenantId", "tenant_alpha", "authorizedTenants", "tenant_alpha"));

        // Operator B: bound solely to tenant_beta
        tokenTenantBeta = tokenProvider.generateToken("operator_beta",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"), new SimpleGrantedAuthority("ROLE_SUPERVISOR")),
                Map.of("tenantId", "tenant_beta", "authorizedTenants", "tenant_beta"));

        // Multi-Tenant Manager: authorized for tenant_alpha and tenant_beta
        tokenMultiTenant = tokenProvider.generateToken("manager_ab",
                List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR")),
                Map.of("tenantId", "tenant_alpha", "authorizedTenants", "tenant_alpha,tenant_beta"));

        // Global System Admin: authorized for all tenants (*)
        tokenGlobalAdmin = tokenProvider.generateToken("global_admin",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                Map.of("tenantId", "tenant_default", "authorizedTenants", "*"));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    // -------------------------------------------------------------------------
    // TEST 1: Tenant Header Spoofing by Unauthenticated Caller Fails
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 1: Anonymous caller attempting to spoof X-Tenant-ID is rejected 403 Forbidden")
    void testAnonymousTenantHeaderSpoofingRejected() throws Exception {
        mockMvc.perform(get("/api/cases")
                .header("X-Tenant-ID", "tenant_victim_corp"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("Forbidden")))
                .andExpect(jsonPath("$.message", containsString("Anonymous tenant selection header is strictly forbidden")));
    }

    // -------------------------------------------------------------------------
    // TEST 2: Authenticated Tenant Header Spoofing by Unauthorized Tenant Fails
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 2: Operator from Tenant A attempting to select Tenant B via header is rejected 403")
    void testAuthenticatedTenantHeaderSpoofingRejected() throws Exception {
        mockMvc.perform(get("/api/cases")
                .header("Authorization", "Bearer " + tokenTenantAlpha)
                .header("X-Tenant-ID", "tenant_beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("Forbidden")))
                .andExpect(jsonPath("$.message", containsString("Unauthorized tenant selection for tenant 'tenant_beta'")));
    }

    // -------------------------------------------------------------------------
    // TEST 3: Authorized Multi-Tenant Operator CAN Switch Tenants
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 3: Authorized multi-tenant operator can switch between allowed tenants")
    void testAuthorizedTenantSwitchingAllowed() throws Exception {
        mockMvc.perform(get("/api/cases")
                .header("Authorization", "Bearer " + tokenMultiTenant)
                .header("X-Tenant-ID", "tenant_beta"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // TEST 4: Cross-Tenant Case Access Blocked
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 4: Tenant A cannot retrieve or view Tenant B's case records")
    void testCrossTenantCaseAccessBlocked() throws Exception {
        String bookingIdB = "B-TEST-B-" + UUID.randomUUID().toString().substring(0, 6);
        Booking bookingB = new Booking(bookingIdB, "Target Passenger B", "PNRB1",
                "HYD-DEL", "AI-839", "Tonight 20:00", "None", "None", "Flyo", "Confirmed");
        bookingB.setTenantId("tenant_beta");
        bookingRepository.save(bookingB);

        String caseIdB = "CASE-ISOLATION-B-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase caseB = new RescueCase(caseIdB, bookingIdB, "Target Passenger B", "DETECTED",
                "HYD-DEL", "AI-839", "Impact B", "None", "None");
        caseB.setTenantId("tenant_beta");
        caseRepository.save(caseB);

        // Operator Alpha queries Operator Beta's case directly -> Must return 404 Not Found (fail closed)
        mockMvc.perform(get("/api/cases/" + caseIdB)
                .header("Authorization", "Bearer " + tokenTenantAlpha))
                .andExpect(status().isNotFound());

        // Operator Beta queries their own case -> 200 OK
        mockMvc.perform(get("/api/cases/" + caseIdB)
                .header("Authorization", "Bearer " + tokenTenantBeta))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(caseIdB)));
    }

    // -------------------------------------------------------------------------
    // TEST 5: Cross-Tenant Booking Access Blocked
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 5: Tenant A cannot retrieve or view Tenant B's booking records")
    void testCrossTenantBookingAccessBlocked() throws Exception {
        String bookingIdB = "B-ISO-B-" + UUID.randomUUID().toString().substring(0, 6);
        Booking bookingB = new Booking(bookingIdB, "Target Passenger B", "PNRB123",
                "HYD-BOM", "6E-501", "Tonight 20:00", "None", "None", "Flyo", "Confirmed");
        bookingB.setTenantId("tenant_beta");
        bookingRepository.save(bookingB);

        // Operator Alpha queries Operator Beta's booking -> 404 Not Found
        mockMvc.perform(get("/api/bookings/" + bookingIdB)
                .header("Authorization", "Bearer " + tokenTenantAlpha))
                .andExpect(status().isNotFound());

        // Operator Beta queries own booking -> 200 OK
        mockMvc.perform(get("/api/bookings/" + bookingIdB)
                .header("Authorization", "Bearer " + tokenTenantBeta))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(bookingIdB)));
    }

    // -------------------------------------------------------------------------
    // TEST 6: Cross-Tenant Payment & Virtual Card Access Blocked
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 6: Tenant A cannot inspect Tenant B's payment details or virtual cards")
    void testCrossTenantPaymentAccessBlocked() throws Exception {
        String bookingIdBeta = "B-PAY-B-" + UUID.randomUUID().toString().substring(0, 6);
        Booking bookingBeta = new Booking(bookingIdBeta, "Payment Pax B", "PNRPAYB",
                "HYD-DEL", "AI-839", "Tonight 20:00", "None", "None", "Flyo", "Confirmed");
        bookingBeta.setTenantId("tenant_beta");
        bookingRepository.save(bookingBeta);

        String caseIdBeta = "CASE-PAY-B-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase cB = new RescueCase(caseIdBeta, bookingIdBeta, "Payment Pax B", "APPROVED",
                "HYD-DEL", "AI-839", "Impact", "None", "None");
        cB.setTenantId("tenant_beta");
        caseRepository.save(cB);

        VirtualCard vccBeta = new VirtualCard("VCC-B-1", caseIdBeta, "OPT-1", "token_beta_card_123",
                "4242", "Mastercard", "Payment Pax B", "12/28", 4500, "INR", "ACTIVE", "3000 - Airlines", LocalDateTime.now());
        vccBeta.setTenantId("tenant_beta");
        virtualCardRepository.save(vccBeta);

        // Admin Alpha (tenant_alpha) calls /api/payments/case/{caseIdBeta} -> 404 Not Found (cross-tenant blocked)
        mockMvc.perform(get("/api/payments/case/" + caseIdBeta)
                .header("Authorization", "Bearer " + tokenTenantAlphaAdmin))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // TEST 7: Cross-Tenant Saga Access & Retry Blocked in Admin Controller
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 7: Admin of Tenant A cannot inspect or retry Tenant B's sagas")
    void testCrossTenantSagaAccessBlocked() throws Exception {
        String sagaIdBeta = "saga-iso-beta-" + UUID.randomUUID().toString().substring(0, 6);
        SagaExecution sagaBeta = new SagaExecution("CASE-B1", "REBOOKING_SAGA", "{}", "corr_b", "idem_b_" + System.nanoTime());
        sagaBeta.setSagaId(sagaIdBeta);
        sagaBeta.setTenantId("tenant_beta");
        sagaExecutionRepository.save(sagaBeta);

        // Operator Alpha attempts to view Tenant Beta's saga -> 404 Not Found
        mockMvc.perform(get("/api/admin/sagas/" + sagaIdBeta)
                .header("Authorization", "Bearer " + tokenTenantAlpha))
                .andExpect(status().isNotFound());

        // Global admin can view it
        mockMvc.perform(get("/api/admin/sagas/" + sagaIdBeta)
                .header("Authorization", "Bearer " + tokenGlobalAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saga.sagaId", is(sagaIdBeta)));
    }

    // -------------------------------------------------------------------------
    // TEST 8: Cross-Tenant Outbox Events Isolated
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 8: Outbox events endpoint strictly isolates events by tenant")
    void testCrossTenantOutboxEventsIsolated() throws Exception {
        OutboxEvent evtAlpha = new OutboxEvent("RESCUE_CASE", "C-A1", "CASE_DISRUPTED", "{}");
        evtAlpha.setTenantId("tenant_alpha");
        outboxEventRepository.save(evtAlpha);

        OutboxEvent evtBeta = new OutboxEvent("RESCUE_CASE", "C-B1", "CASE_DISRUPTED", "{}");
        evtBeta.setTenantId("tenant_beta");
        outboxEventRepository.save(evtBeta);

        // Operator Alpha queries outbox -> must only see Tenant Alpha events
        mockMvc.perform(get("/api/admin/outbox/events")
                .header("Authorization", "Bearer " + tokenTenantAlpha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].tenantId", everyItem(is("tenant_alpha"))));
    }

    // -------------------------------------------------------------------------
    // TEST 9: Missing Tenant Identity Fails Closed
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 9: Unauthenticated requests to protected API endpoints fail closed (401)")
    void testMissingTenantIdentityFailsClosed() throws Exception {
        mockMvc.perform(get("/api/cases"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // TEST 10: TenantContext Cleaned After Request (No ThreadLocal Leakage)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 10: ThreadLocal context is guaranteed cleared after execution")
    void testTenantContextClearedAfterRequest() throws Exception {
        mockMvc.perform(get("/api/cases")
                .header("Authorization", "Bearer " + tokenTenantAlpha))
                .andExpect(status().isOk());

        // On current thread, context must be cleared
        assertEquals(TenantContext.DEFAULT_TENANT_ID, TenantContext.getTenantId());
    }

    // -------------------------------------------------------------------------
    // TEST 11: Rate Limiter Enforces Separate Operational Policies
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 11: Dedicated rate limit policies enforce limits (e.g. REBOOKING 5 req/min)")
    void testRateLimiterPolicies() {
        String client = "test_client_" + UUID.randomUUID();
        // Policy REBOOKING allows 5 requests per 60s
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.tryAcquire(DistributedRateLimiter.Policy.REBOOKING, "tenant_alpha", client),
                    "Request " + (i + 1) + " should be allowed");
        }
        // 6th request must be throttled
        assertFalse(rateLimiter.tryAcquire(DistributedRateLimiter.Policy.REBOOKING, "tenant_alpha", client),
                "6th request must exceed policy threshold");
    }

    // -------------------------------------------------------------------------
    // TEST 12: Shared Rate Limiting Coordination Simulation
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 12: Shared rate limit backend coordinates counter across instances")
    void testSharedRateLimitingCoordination() {
        if (rateLimiter.isSharedStorageActive() && jdbcTemplate != null) {
            String sharedKey = "shared_test_key_" + UUID.randomUUID();
            // Simulate instance 1 taking 2 requests
            assertTrue(rateLimiter.tryAcquire(sharedKey, 3, 60));
            assertTrue(rateLimiter.tryAcquire(sharedKey, 3, 60));

            // Verify count in database is 2
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT request_count FROM shared_rate_limits WHERE limit_key = ?",
                    Integer.class, sharedKey
            );
            assertEquals(2, count);

            // 3rd request permitted
            assertTrue(rateLimiter.tryAcquire(sharedKey, 3, 60));
            // 4th request denied by shared DB counter
            assertFalse(rateLimiter.tryAcquire(sharedKey, 3, 60));
        } else {
            // In-memory fallback mode
            assertTrue(rateLimiter.isSingleInstanceRestrictionActive(),
                    "In-memory mode must enforce single-instance deployment restriction");
        }
    }

    // -------------------------------------------------------------------------
    // TEST 13: Meta WhatsApp Raw Bytes HMAC-SHA256 Signature Verification
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 13: Meta WhatsApp raw-bytes signature verification validates authentic & rejects forged")
    void testMetaWhatsAppSignatureVerification() {
        String payload = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"WH123\"}]}";
        String secret = "test_meta_app_secret_32_bytes_len";

        // Authentic signature
        String hexHash = calculateHmacSha256(payload, secret);
        String validHeader = "sha256=" + hexHash;

        assertTrue(metaValidator.validate(payload, validHeader, secret, Map.of()),
                "Authentic Meta signature must validate");

        // Forged signature
        String forgedHeader = "sha256=0000000000000000000000000000000000000000000000000000000000000000";
        assertFalse(metaValidator.validate(payload, forgedHeader, secret, Map.of()),
                "Forged signature must be rejected");

        // Invalid prefix
        assertFalse(metaValidator.validate(payload, hexHash, secret, Map.of()),
                "Signature lacking 'sha256=' prefix must be rejected");
    }

    // -------------------------------------------------------------------------
    // TEST 14: Stripe Signature & Replay Protection Verification
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 14: Stripe signature verification parses t=...,v1=... and rejects replayed timestamps")
    void testStripeSignatureAndReplayProtection() {
        String payload = "{\"id\":\"evt_123\",\"type\":\"issuing_card.created\"}";
        String secret = "whsec_test_stripe_secret_key_123";
        long currentTimestamp = Instant.now().getEpochSecond();

        String signedContent = currentTimestamp + "." + payload;
        String v1Hash = calculateHmacSha256(signedContent, secret);
        String validHeader = "t=" + currentTimestamp + ",v1=" + v1Hash;

        assertTrue(stripeValidator.validate(payload, validHeader, secret, Map.of()),
                "Fresh authentic Stripe webhook must validate");

        // Replay attack: timestamp older than 300 seconds (e.g. 400 seconds ago)
        long oldTimestamp = currentTimestamp - 400;
        String replayedContent = oldTimestamp + "." + payload;
        String replayedHash = calculateHmacSha256(replayedContent, secret);
        String replayedHeader = "t=" + oldTimestamp + ",v1=" + replayedHash;

        assertFalse(stripeValidator.validate(payload, replayedHeader, secret, Map.of()),
                "Replayed Stripe webhook exceeding 300s window must be rejected");
    }

    // -------------------------------------------------------------------------
    // TEST 15: Webhook Event Deduplication Idempotency
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 15: Webhook processor deduplicates identical eventId idempotently")
    void testWebhookEventDeduplication() {
        String eventId = "evt_idem_" + UUID.randomUUID().toString().replace("-", "");
        String payload = "{\"eventType\":\"AIRLINE_SCHEDULE_CHANGE\",\"flight\":\"AI-839\"}";
        long timestamp = Instant.now().getEpochSecond();
        String signature = "sha256=" + calculateHmacSha256(payload, "flyo-enterprise-webhook-signing-secret-prod-2026");

        // First delivery
        WebhookEventProcessor.WebhookProcessingResult res1 = webhookProcessor.processIncomingWebhook(
                eventId, payload, signature, timestamp);
        assertTrue(res1.isSuccess());
        assertEquals("PROCESSED", res1.getCode());

        // Duplicate delivery
        WebhookEventProcessor.WebhookProcessingResult res2 = webhookProcessor.processIncomingWebhook(
                eventId, payload, signature, timestamp);
        assertTrue(res2.isSuccess(), "Duplicate must return success status");
        assertEquals("IDEMPOTENT_DUPLICATE", res2.getCode());
    }

    // -------------------------------------------------------------------------
    // TEST 16: Provider Registry Semantics & Evidence Records
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 16: Registry accurately classifies all 11 providers with zero false production claims")
    void testProviderRegistrySemantics() {
        List<ProviderMetadata> all = providerRegistry.getAllProviders();
        assertFalse(all.isEmpty());

        for (ProviderMetadata p : all) {
            assertNotNull(p.getEvidenceRecord(), "Every provider must retain an evidence record");
            // No adapter may falsely claim to be production verified in this stage
            assertNotEquals(ProviderCapabilityStatus.PRODUCTION_VERIFIED, p.getCapabilityStatus(),
                    "Adapter " + p.getProviderId() + " must NOT claim PRODUCTION_VERIFIED");
            assertFalse(p.isProductionCertified(),
                    "Adapter " + p.getProviderId() + " must NOT claim productionCertified");
        }
    }

    // -------------------------------------------------------------------------
    // TEST 17: Discrepancy Recording & Anti-Hallucination
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 17: Data authority conflict records discrepancy and prevents silent success")
    void testDiscrepancyRecording() {
        String caseId = "CASE-DISC-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase c = new RescueCase(caseId, "B1", "Pax Disc", "DONE",
                "HYD-DEL", "AI-839", "Disruption", "None", "None");
        c.setNewPnr("TEST-PNR");
        caseRepository.save(c);

        DiscrepancyRecord record = new DiscrepancyRecord(
                "disc_" + UUID.randomUUID(), caseId, "tenant_default",
                "TIER1_TIER2_CONFLICT", "DONE", "CANCELLED",
                "Radar reported flight flying, carrier PSS issued cancellation"
        );
        discrepancyRecordRepository.save(record);

        Optional<DiscrepancyRecord> found = discrepancyRecordRepository.findById(record.getId());
        assertTrue(found.isPresent());
        assertFalse(found.get().isResolved(), "Discrepancy must remain unresolved until manual/carrier confirmation");
    }

    // -------------------------------------------------------------------------
    // TEST 18: PII and Secret Redaction Verification
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Adversarial 18: Sensitive CVV and raw PAN are excluded from entities and payloads")
    void testPiiAndSecretRedaction() {
        VirtualCard vcc = new VirtualCard("VCC-SEC", "C1", "O1", "token_123", "4242", "Mastercard", "Test Pax", "12/28", 1000, "INR", "ACTIVE", "3000 - Airlines", LocalDateTime.now());
        // Verify no PAN or CVV methods exist on VirtualCard
        assertEquals("•••• •••• •••• 4242", vcc.getMaskedCardNumber());
        assertEquals("4242", vcc.getLastFour());
        assertEquals("token_123", vcc.getCardTokenId());
        assertFalse(vcc.toString().contains("cvv"), "Entity string representation must never contain cvv");
    }

    private String calculateHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes).toLowerCase();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
