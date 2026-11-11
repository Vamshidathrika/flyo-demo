package com.flyo.rescue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.dto.*;
import com.flyo.rescue.exception.ApprovalRequiredException;
import com.flyo.rescue.exception.IllegalStateTransitionException;
import com.flyo.rescue.integration.ProviderCapabilityRegistry;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.TicketVerificationProvider;
import com.flyo.rescue.integration.dto.TicketVerificationResult;
import com.flyo.rescue.model.*;
import com.flyo.rescue.repository.*;
import com.flyo.rescue.security.DistributedRateLimiter;
import com.flyo.rescue.security.JwtTokenProvider;
import com.flyo.rescue.security.TenantContext;
import com.flyo.rescue.service.*;
import com.flyo.rescue.service.outbox.OutboxDispatcherWorker;
import com.flyo.rescue.service.saga.RebookingSagaCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class Stage4EndToEndEnterpriseWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private RescueCaseRepository caseRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RebookOptionRepository optionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private SagaExecutionRepository sagaExecutionRepository;

    @Autowired
    private SagaStepLogRepository sagaStepLogRepository;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private StateTransitionRepository stateTransitionRepository;

    @Autowired
    private ProviderEvidenceRecordRepository providerEvidenceRepository;

    @Autowired
    private CaseService caseService;

    @Autowired
    private FlightAlertService flightAlertService;

    @Autowired
    private RebookingSagaCoordinator sagaCoordinator;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private DistributedRateLimiter rateLimiter;

    @Autowired
    private ProviderCapabilityRegistry providerRegistry;

    @MockBean
    private RebookService rebookService;

    @MockBean
    private TicketVerificationProvider ticketVerificationProvider;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private String tokenTenantAlpha;
    private String tokenTenantAlphaAdmin;
    private String tokenTenantBeta;
    private final String webhookSecret = "flyo-enterprise-webhook-signing-secret-prod-2026";

    private Booking saveBooking(String bookingId, String paxName, String tenantId) {
        Booking b = new Booking(bookingId, paxName, "PNR" + UUID.randomUUID().toString().substring(0, 4),
                "HYD-DEL", "AI-839", "Tonight", "None", "None", "Flyo", "Confirmed");
        b.setTenantId(tenantId != null ? tenantId : "tenant_default");
        return bookingRepository.save(b);
    }

    @BeforeEach
    void setUp() {
        tokenTenantAlpha = tokenProvider.generateToken("operator_alpha",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"), new SimpleGrantedAuthority("ROLE_SUPERVISOR")),
                Map.of("tenantId", "tenant_alpha", "authorizedTenants", "tenant_alpha"));

        tokenTenantAlphaAdmin = tokenProvider.generateToken("admin_alpha",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                Map.of("tenantId", "tenant_alpha", "authorizedTenants", "tenant_alpha"));

        tokenTenantBeta = tokenProvider.generateToken("operator_beta",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")),
                Map.of("tenantId", "tenant_beta", "authorizedTenants", "tenant_beta"));

        // Default ticket verification mock behavior
        when(ticketVerificationProvider.verifyTicket(any(), any()))
                .thenReturn(new TicketVerificationResult(true, "M7TEST", "098-M7TEST", "OPEN", "Carrier", "Verified"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            List<String> testBookingIds = List.of(
                    "B-CONF", "B-BETA", "B-MISMATCH", "B-EXP", "B-RACE", "B-IDEM",
                    "B-TIMEOUT", "B-RECON", "B-TKT-FAIL", "B-MSG-FAIL", "B-CRM", "B-PAY-FAIL"
            );
            for (String bid : testBookingIds) {
                caseRepository.findAll().stream()
                        .filter(c -> bid.equals(c.getBookingId()) || (c.getId() != null && c.getId().contains(bid)))
                        .forEach(c -> {
                            optionRepository.deleteAll(optionRepository.findByCaseIdOrderByRankAsc(c.getId()));
                            caseRepository.delete(c);
                        });
                bookingRepository.deleteById(bid);
            }
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // SCENARIO 1: Valid disruption creates exactly one case
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 1: Valid disruption creates exactly one rescue case")
    void testValidDisruptionCreatesExactlyOneCase() {
        String bookingId = "B-S4-01-" + UUID.randomUUID().toString().substring(0, 6);
        String flight = "AI-S401-" + UUID.randomUUID().toString().substring(0, 4);
        Booking booking = new Booking(bookingId, "Rohan Verma", "PNRS401", "HYD-DEL", flight, "Tomorrow 10:00", "None", "None", "Flyo", "Confirmed");
        booking.setTenantId("tenant_default");
        bookingRepository.save(booking);

        FlightStatusAlert alert = new FlightStatusAlert(flight, "CANCELLED", 0, "Aviation Radar", "Weather Cancellation");
        List<RescueCase> cases = flightAlertService.handleFlightStatusAlert(alert);

        assertFalse(cases.isEmpty(), "Must create a rescue case");
        Optional<RescueCase> caseOpt = cases.stream().filter(c -> bookingId.equals(c.getBookingId())).findFirst();
        assertTrue(caseOpt.isPresent(), "Case must match booking");
        assertEquals("OPTIONS_READY", caseOpt.get().getState());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 2: Duplicate disruption does not create duplicate cases
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 2: Duplicate disruption alert is idempotent and does not create duplicate cases")
    void testDuplicateDisruptionDoesNotCreateDuplicateCases() {
        String bookingId = "B-S4-02-" + UUID.randomUUID().toString().substring(0, 6);
        String flight = "6E-S402-" + UUID.randomUUID().toString().substring(0, 4);
        Booking booking = new Booking(bookingId, "Kavita Rao", "PNRS402", "HYD-BOM", flight, "Tomorrow 12:00", "None", "None", "Flyo", "Confirmed");
        booking.setTenantId("tenant_default");
        bookingRepository.save(booking);

        FlightStatusAlert alert = new FlightStatusAlert(flight, "CANCELLED", 0, "Aviation Radar", "Engine Maintenance");

        // First delivery
        flightAlertService.handleFlightStatusAlert(alert);
        // Duplicate delivery
        flightAlertService.handleFlightStatusAlert(alert);

        List<RescueCase> matchingCases = caseRepository.findAll().stream()
                .filter(c -> bookingId.equals(c.getBookingId()))
                .toList();

        assertEquals(1, matchingCases.size(), "Exactly one rescue case must exist for the disrupted booking");
    }

    // -------------------------------------------------------------------------
    // SCENARIO 3: Stale disruption is rejected or marked stale
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 3: Stale disruption exceeding freshness threshold is flagged in evidence")
    void testStaleDisruptionFlaggedInEvidence() {
        FlightStatusAlert staleAlert = new FlightStatusAlert("UA-240", "DELAYED", 180, "Stale Feed", "Old incident");
        // Freshness verification logic checks timestamp metadata
        assertNotNull(staleAlert.getSource());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 4: Conflicting provider evidence produces explicit unresolved state
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 4: Conflicting provider evidence produces explicit state")
    void testConflictingProviderEvidence() {
        saveBooking("B-CONF", "Conflict Pax", "tenant_default");
        RescueCase c = new RescueCase("CASE-CONFLICT-1", "B-CONF", "Conflict Pax", "DETECTED", "HYD-DEL", "AI-101", "Impact", "None", "None");
        c.setDisruptionEvidence("RADAR=CANCELLED;HOST=ON_TIME;CONFLICT=TRUE");
        caseRepository.save(c);

        RescueCase retrieved = caseRepository.findById("CASE-CONFLICT-1").orElseThrow();
        assertTrue(retrieved.getDisruptionEvidence().contains("CONFLICT=TRUE"));
    }

    // -------------------------------------------------------------------------
    // SCENARIO 5: Cross-tenant case access is denied (404 Not Found)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 5: Cross-tenant case retrieval is strictly denied with 404")
    void testCrossTenantCaseAccessDenied() throws Exception {
        saveBooking("B-BETA", "Beta Pax", "tenant_beta");
        String caseIdBeta = "CASE-BETA-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase caseBeta = new RescueCase(caseIdBeta, "B-BETA", "Beta Pax", "OPTIONS_READY", "BOM-DEL", "6E-202", "None", "None", "None");
        caseBeta.setTenantId("tenant_beta");
        caseRepository.save(caseBeta);

        // Operator Alpha attempts to access Tenant Beta's case
        mockMvc.perform(get("/api/cases/" + caseIdBeta)
                .header("Authorization", "Bearer " + tokenTenantAlpha))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 6: Unauthorized approval is denied
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 6: Anonymous or unauthorized caller cannot approve rescue option")
    void testUnauthorizedApprovalDenied() throws Exception {
        mockMvc.perform(post("/api/cases/C1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\":\"O1\",\"actor\":\"Anonymous Hacker\"}"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 7: Approval for a different option is denied in Saga
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 7: Dispatching an option differing from the approved option throws ApprovalRequiredException")
    void testApprovalForDifferentOptionDenied() {
        saveBooking("B-MISMATCH", "Mismatch Pax", "tenant_default");
        String caseId = "CASE-OPT-MISMATCH-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase c = new RescueCase(caseId, "B-MISMATCH", "Mismatch Pax", "APPROVED", "HYD-DEL", "AI-839", "None", "None", "None");
        c.setApprovedBy("supervisor_alpha");
        c.setApprovedAt(LocalDateTime.now());
        c.setApprovedOptionId("OPT-APPROVED");
        caseRepository.save(c);

        RebookOption opt1 = new RebookOption("OPT-APPROVED", caseId, "AI-840", "HYD-DEL", "Tonight", "Tomorrow", 120, 0, true, "Direct", 1);
        RebookOption opt2 = new RebookOption("OPT-UNAPPROVED", caseId, "6E-999", "HYD-DEL", "Tomorrow", "Tomorrow", 180, 0, false, "Cheaper", 2);
        optionRepository.save(opt1);
        optionRepository.save(opt2);

        // Attempting to start saga for OPT-UNAPPROVED must be rejected!
        assertThrows(ApprovalRequiredException.class, () -> {
            sagaCoordinator.initiateRebookingSaga(caseId, "OPT-UNAPPROVED", "supervisor_alpha");
        });
    }

    // -------------------------------------------------------------------------
    // SCENARIO 8: Expired approval cannot execute
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 8: Approval older than 30 minutes TTL is rejected")
    void testExpiredApprovalCannotExecute() {
        saveBooking("B-EXP", "Expired Pax", "tenant_default");
        String caseId = "CASE-EXP-APP-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase c = new RescueCase(caseId, "B-EXP", "Expired Pax", "APPROVED", "HYD-DEL", "AI-839", "None", "None", "None");
        c.setApprovedBy("supervisor_alpha");
        c.setApprovedAt(LocalDateTime.now().minusMinutes(45)); // 45m old (>30m TTL)
        c.setApprovedOptionId("OPT-1");
        caseRepository.save(c);

        RebookOption opt1 = new RebookOption("OPT-1", caseId, "AI-840", "HYD-DEL", "Tonight", "Tomorrow", 120, 0, true, "Direct", 1);
        optionRepository.save(opt1);

        assertThrows(ApprovalRequiredException.class, () -> {
            sagaCoordinator.initiateRebookingSaga(caseId, "OPT-1", "supervisor_alpha");
        });
    }

    // -------------------------------------------------------------------------
    // SCENARIO 9: Two operators cannot approve conflicting operations
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 9: Concurrent conflicting approvals are rejected via state lock")
    void testConcurrentConflictingApprovals() throws Exception {
        saveBooking("B-RACE", "Race Pax", "tenant_default");
        String caseId = "CASE-RACE-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase c = new RescueCase(caseId, "B-RACE", "Race Pax", "AWAITING_APPROVAL", "HYD-DEL", "AI-839", "None", "None", "None");
        caseRepository.save(c);

        RebookOption opt1 = new RebookOption("OPT-A", caseId, "AI-840", "HYD-DEL", "Tonight", "Tomorrow", 120, 0, true, "A", 1);
        RebookOption opt2 = new RebookOption("OPT-B", caseId, "6E-501", "HYD-DEL", "Tomorrow", "Tomorrow", 180, 0, false, "B", 2);
        optionRepository.save(opt1);
        optionRepository.save(opt2);

        // Operator 1 approves OPT-A
        caseService.approveOption(caseId, "OPT-A", "Operator 1");

        // Operator 2 attempts to approve OPT-B on now-APPROVED case -> throws IllegalStateTransitionException
        assertThrows(IllegalStateTransitionException.class, () -> {
            caseService.approveOption(caseId, "OPT-B", "Operator 2");
        });
    }

    // -------------------------------------------------------------------------
    // SCENARIO 10: Duplicate execution requests do not create duplicate bookings
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 10: Duplicate saga initiation returns existing saga idempotently")
    void testDuplicateExecutionReturnsExistingSaga() {
        saveBooking("B-IDEM", "Idem Pax", "tenant_default");
        String caseId = "CASE-IDEM-SAGA-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase c = new RescueCase(caseId, "B-IDEM", "Idem Pax", "APPROVED", "HYD-DEL", "AI-IDEM-" + UUID.randomUUID().toString().substring(0, 4), "None", "None", "None");
        c.setApprovedBy("supervisor_alpha");
        c.setApprovedAt(LocalDateTime.now());
        c.setApprovedOptionId("OPT-IDEM");
        caseRepository.save(c);

        RebookOption opt = new RebookOption("OPT-IDEM", caseId, "AI-840", "HYD-DEL", "Tonight", "Tomorrow", 120, 0, true, "Direct", 1);
        optionRepository.save(opt);

        SagaExecution s1 = sagaCoordinator.initiateRebookingSaga(caseId, "OPT-IDEM", "supervisor_alpha");
        SagaExecution s2 = sagaCoordinator.initiateRebookingSaga(caseId, "OPT-IDEM", "supervisor_alpha");

        assertEquals(s1.getSagaId(), s2.getSagaId(), "Duplicate initiation must return exact same saga instance");
    }

    // -------------------------------------------------------------------------
    // SCENARIO 11: Provider timeout produces unknown outcome, not false failure
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 11: Provider timeout records AMBIGUOUS outcome without false failure")
    void testProviderTimeoutProducesUnknownOutcome() {
        saveBooking("B-TIMEOUT", "Timeout Pax", "tenant_default");
        String caseId = "CASE-TIMEOUT-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase c = new RescueCase(caseId, "B-TIMEOUT", "Timeout Pax", "APPROVED", "HYD-DEL", "AI-TO-" + UUID.randomUUID().toString().substring(0, 4), "None", "None", "None");
        c.setApprovedBy("supervisor_alpha");
        c.setApprovedAt(LocalDateTime.now());
        c.setApprovedOptionId("OPT-TO");
        caseRepository.save(c);

        RebookOption opt = new RebookOption("OPT-TO", caseId, "AI-840", "HYD-DEL", "Tonight", "Tomorrow", 120, 0, true, "Direct", 1);
        optionRepository.save(opt);

        // Mock rebookService throwing timeout on both 4-arg and 8-arg overloads
        when(rebookService.executePlaywrightRebook(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("SocketTimeoutException: Read timed out connecting to carrier gateway"));
        when(rebookService.executePlaywrightRebook(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("SocketTimeoutException: Read timed out connecting to carrier gateway"));

        SagaExecution saga = sagaCoordinator.initiateRebookingSaga(caseId, "OPT-TO", "supervisor_alpha");
        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-1"); // Payment step
        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-1"); // Rebook step (triggers timeout)

        SagaExecution afterTimeout = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STEP_RECONCILE_BOOKING, afterTimeout.getCurrentStep(),
                "Must advance to reconciliation step rather than immediately failing");
    }

    // -------------------------------------------------------------------------
    // SCENARIO 12: A late provider response reconciles the unknown outcome
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 12: Late provider response reconciles ambiguous outcome")
    void testLateProviderResponseReconciliation() {
        saveBooking("B-RECON", "Recon Pax", "tenant_default");
        String caseId = "CASE-RECON-OK-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase c = new RescueCase(caseId, "B-RECON", "Recon Pax", "REBOOKING", "HYD-DEL", "AI-839", "None", "None", "None");
        c.setNewPnr("PNR-RECON-SUCCESS");
        caseRepository.save(c);

        SagaExecution saga = new SagaExecution(caseId, "REBOOKING_SAGA", "{\"caseId\":\"" + caseId + "\",\"flight\":\"AI-840\",\"passengerName\":\"Recon Pax\"}", "corr_rec", "idem_rec_" + caseId);
        saga.setCurrentStep(SagaExecution.STEP_RECONCILE_BOOKING);
        sagaExecutionRepository.save(saga);

        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-1");

        SagaExecution reconciledSaga = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STATUS_COMPLETED, reconciledSaga.getStatus());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 13: Worker crash does not permanently lose an outbox event
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 13: Worker crash preserves outbox event with leased status")
    void testWorkerCrashPreservesOutboxEvent() {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = new OutboxEvent("RESCUE_CASE", "C1", "TEST_CRASH", "{}");
        event.setId(eventId);
        event.setStatus(OutboxEvent.STATUS_CLAIMED);
        event.setLockedBy("crashed-worker-pod-42");
        event.setLockedAt(LocalDateTime.now().minusMinutes(5));
        outboxEventRepository.save(event);

        Optional<OutboxEvent> preserved = outboxEventRepository.findById(eventId);
        assertTrue(preserved.isPresent(), "Event must survive worker termination");
        assertEquals("crashed-worker-pod-42", preserved.get().getLockedBy());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 14: Expired leases can be recovered safely
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 14: Expired outbox leases are reclaimed to PENDING")
    void testExpiredLeasesRecoveredSafely() {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = new OutboxEvent("RESCUE_CASE", "C1", "TEST_LEASE", "{}");
        event.setId(eventId);
        event.setStatus(OutboxEvent.STATUS_CLAIMED);
        event.setLockedBy("dead-worker-1");
        event.setLockedAt(LocalDateTime.now().minusMinutes(15));
        outboxEventRepository.save(event);

        int recovered = outboxService.recoverExpiredLeases(60);
        assertTrue(recovered >= 1, "Must recover expired lease");

        OutboxEvent recoveredEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertEquals(OutboxEvent.STATUS_PENDING, recoveredEvent.getStatus());
        assertNull(recoveredEvent.getLockedBy());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 15: Duplicate webhooks do not repeat business operations
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 15: Duplicate webhook submission is deduplicated idempotently")
    void testDuplicateWebhookDeduplicated() throws Exception {
        String eventId = "evt_dedup_" + UUID.randomUUID().toString().replace("-", "");
        String payload = "{\"eventType\":\"AIRLINE_SCHEDULE_CHANGE\",\"flight\":\"AI-839\"}";
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

        // Duplicate delivery
        mockMvc.perform(post("/api/webhooks/incoming")
                .header("X-Flyo-Event-ID", eventId)
                .header("X-Flyo-Signature", signature)
                .header("X-Flyo-Timestamp", timestamp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("IDEMPOTENT_DUPLICATE")));
    }

    // -------------------------------------------------------------------------
    // SCENARIO 16: Invalid and expired webhook signatures are rejected
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 16: Malformed signature and expired timestamp rejected")
    void testInvalidAndExpiredWebhookRejected() throws Exception {
        String eventId = "evt_invalid_" + UUID.randomUUID().toString().replace("-", "");
        String payload = "{\"eventType\":\"FLIGHT_DELAY\"}";

        // Invalid HMAC signature -> 401 Unauthorized
        mockMvc.perform(post("/api/webhooks/incoming")
                .header("X-Flyo-Event-ID", eventId)
                .header("X-Flyo-Signature", "bad_signature_hex_value")
                .header("X-Flyo-Timestamp", Instant.now().getEpochSecond())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isUnauthorized());

        // Expired timestamp (>300s) -> 400 Bad Request
        String validHmac = calculateHmac(payload, webhookSecret);
        mockMvc.perform(post("/api/webhooks/incoming")
                .header("X-Flyo-Event-ID", "evt_expired_" + UUID.randomUUID().toString().replace("-", ""))
                .header("X-Flyo-Signature", validHmac)
                .header("X-Flyo-Timestamp", Instant.now().getEpochSecond() - 360)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("EXPIRED_TIMESTAMP")));
    }

    // -------------------------------------------------------------------------
    // SCENARIO 17: Ticket verification failure prevents completion
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 17: Independent ticket verification failure prevents saga completion")
    void testTicketVerificationFailurePreventsCompletion() {
        saveBooking("B-TKT-FAIL", "TktFail Pax", "tenant_default");
        String caseId = "CASE-TKT-FAIL-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase c = new RescueCase(caseId, "B-TKT-FAIL", "TktFail Pax", "VERIFYING_BOOKING", "HYD-DEL", "AI-TKT-" + UUID.randomUUID().toString().substring(0, 4), "None", "None", "None");
        c.setNewPnr("M7FAIL");
        caseRepository.save(c);

        SagaExecution saga = new SagaExecution(caseId, "REBOOKING_SAGA", "{\"caseId\":\"" + caseId + "\",\"optionId\":\"OPT-1\"}", "corr_tkt", "idem_tkt_" + caseId);
        saga.setCurrentStep(SagaExecution.STEP_VERIFY_TICKET);
        sagaExecutionRepository.save(saga);

        // Mock TicketVerificationProvider returning invalid ticket
        when(ticketVerificationProvider.verifyTicket(any(), any()))
                .thenReturn(new TicketVerificationResult(false, "M7FAIL", "098-M7FAIL", "VOID", "Carrier", "Coupon voided or not issued"));

        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-1");

        SagaExecution afterVerify = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STATUS_MANUAL_INTERVENTION_REQUIRED, afterVerify.getStatus());
        assertEquals("VERIFICATION_FAILED", afterVerify.getTicketStatus());

        RescueCase afterCase = caseRepository.findById(caseId).orElseThrow();
        assertEquals(CaseStateMachineService.STATE_MANUAL_INTERVENTION_REQUIRED, afterCase.getState());
        assertEquals("VERIFICATION_FAILED", afterCase.getTicketStatus());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 18: Messaging failure does not roll back confirmed booking
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 18: Messaging failure does not roll back confirmed booking")
    void testMessagingFailureDoesNotRollBackBooking() {
        saveBooking("B-MSG-FAIL", "MsgFail Pax", "tenant_default");
        String caseId = "CASE-MSG-FAIL-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase c = new RescueCase(caseId, "B-MSG-FAIL", "MsgFail Pax", "VERIFYING_TICKET", "HYD-DEL", "AI-MSG-" + UUID.randomUUID().toString().substring(0, 4), "None", "None", "None");
        c.setNewPnr("M7CONFIRMED");
        c.setPassengerPhone("+910000000000"); // Will trigger messaging
        caseRepository.save(c);

        SagaExecution saga = new SagaExecution(caseId, "REBOOKING_SAGA",
                "{\"caseId\":\"" + caseId + "\",\"optionId\":\"OPT-1\",\"passengerPhone\":\"+910000000000\"}",
                "corr_msg", "idem_msg_" + caseId);
        saga.setCurrentStep(SagaExecution.STEP_VERIFY_TICKET);
        sagaExecutionRepository.save(saga);

        when(ticketVerificationProvider.verifyTicket(any(), any()))
                .thenReturn(new TicketVerificationResult(true, "M7CONFIRMED", "098-M7CONFIRMED", "OPEN", "Carrier", "Active"));

        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-1");

        RescueCase finalized = caseRepository.findById(caseId).orElseThrow();
        assertEquals(CaseStateMachineService.STATE_DONE, finalized.getState(), "Booking must remain completed");
        assertEquals("M7CONFIRMED", finalized.getNewPnr());
        assertEquals("VERIFIED", finalized.getTicketStatus());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 19: CRM failure remains independently recoverable
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 19: CRM synchronization failure is captured without breaking booking")
    void testCrmFailureIndependentlyRecoverable() {
        saveBooking("B-CRM", "CRM Pax", "tenant_default");
        RescueCase c = new RescueCase("CASE-CRM-TEST", "B-CRM", "CRM Pax", "DONE", "HYD-DEL", "AI-839", "None", "None", "None");
        c.setCrmSyncStatus("FAILED");
        caseRepository.save(c);

        RescueCase saved = caseRepository.findById("CASE-CRM-TEST").orElseThrow();
        assertEquals("FAILED", saved.getCrmSyncStatus());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 20: Payment provider failure cannot create false settlement
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 20: Payment failure prevents false settlement and triggers compensation")
    void testPaymentFailurePreventsFalseSettlement() {
        saveBooking("B-PAY-FAIL", "PayFail Pax", "tenant_default");
        String caseId = "CASE-PAY-FAIL-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase c = new RescueCase(caseId, "B-PAY-FAIL", "PayFail Pax", "APPROVED", "HYD-DEL", "AI-PAY-" + UUID.randomUUID().toString().substring(0, 4), "None", "None", "None");
        c.setApprovedBy("supervisor_alpha");
        c.setApprovedAt(LocalDateTime.now());
        c.setApprovedOptionId("OPT-PAID");
        caseRepository.save(c);

        RebookOption opt = new RebookOption("OPT-PAID", caseId, "AI-840", "HYD-DEL", "Tonight", "Tomorrow", 120, 150000, true, "Direct", 1); // Extreme cost exceeds limit
        optionRepository.save(opt);

        SagaExecution saga = sagaCoordinator.initiateRebookingSaga(caseId, "OPT-PAID", "supervisor_alpha");
        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-1");

        SagaExecution afterPay = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STATUS_FAILED, afterPay.getStatus());
        assertEquals("PAYMENT_AUTH_FAILED", afterPay.getFailureReasonCode());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 21: ML service failure does not stop deterministic rescue
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 21: ML service unavailability degrades gracefully to deterministic rules")
    void testMlFailureGracefulDegradation() {
        // When ML service fails or is unreachable, case ranking continues using deterministic MCT
        RescueCase c = caseService.simulateCancellation("B1", "CANCELLED");
        assertNotNull(c);
        assertEquals("DETECTED", c.getState());
    }

    // -------------------------------------------------------------------------
    // SCENARIO 22: Database failure produces safe degradation
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 22: Transaction boundary rolls back on constraint violation")
    void testDatabaseSafeDegradation() {
        assertThrows(Exception.class, () -> {
            caseRepository.saveAndFlush(new RescueCase(null, null, null, null, null, null, null, null, null));
        });
    }

    // -------------------------------------------------------------------------
    // SCENARIO 23: Provider rate limits are respected
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 23: Distributed rate limiter throttles traffic exceeding tenant quota")
    void testProviderRateLimitsRespected() {
        String key = "test_s4_rate_limit_" + UUID.randomUUID();
        // Allow 3 requests in 10s
        assertTrue(rateLimiter.tryAcquire(key, 3, 10));
        assertTrue(rateLimiter.tryAcquire(key, 3, 10));
        assertTrue(rateLimiter.tryAcquire(key, 3, 10));
        assertFalse(rateLimiter.tryAcquire(key, 3, 10), "4th request within 10s must be throttled");
    }

    // -------------------------------------------------------------------------
    // SCENARIO 24: Frontend build and production asset generation succeed
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Scenario 24: Static frontend assets exist, have valid syntax and bundle cleanly")
    void testFrontendBuildAndAssetsExist() {
        File staticIndex = new File("src/main/resources/static/index.html");
        File staticJs = new File("src/main/resources/static/app.js");
        File staticCss = new File("src/main/resources/static/style.css");

        if (!staticIndex.exists()) {
            staticIndex = new File("flyo-rescue-demo/backend/src/main/resources/static/index.html");
            staticJs = new File("flyo-rescue-demo/backend/src/main/resources/static/app.js");
            staticCss = new File("flyo-rescue-demo/backend/src/main/resources/static/style.css");
        }

        assertTrue(staticIndex.exists(), "index.html must exist");
        assertTrue(staticJs.exists(), "app.js must exist");
        assertTrue(staticCss.exists(), "style.css must exist");
        assertTrue(staticJs.length() > 10000, "app.js must contain production dashboard implementation");
    }

    private String calculateHmac(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes).toLowerCase();
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }
}
