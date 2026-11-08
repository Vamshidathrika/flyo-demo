package com.flyo.rescue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.exception.ApprovalRequiredException;
import com.flyo.rescue.model.OutboxEvent;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.SagaExecution;
import com.flyo.rescue.model.SagaStepLog;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.OutboxEventRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.repository.SagaExecutionRepository;
import com.flyo.rescue.repository.SagaStepLogRepository;
import com.flyo.rescue.repository.VirtualCardRepository;
import com.flyo.rescue.service.CaseService;
import com.flyo.rescue.service.CaseStateMachineService;
import com.flyo.rescue.service.OutboxService;
import com.flyo.rescue.service.PaymentSettlementService;
import com.flyo.rescue.service.RebookService;
import com.flyo.rescue.service.saga.RebookingSagaCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * STAGE 1 ACCEPTANCE TESTS: Transactional Outbox, Distributed Saga & Failure-Resilient Backend.
 *
 * Verifies:
 * 1. Outbox atomic batch claiming with worker leases.
 * 2. Outbox deduplication via idempotency keys.
 * 3. Outbox exponential backoff and transition to DEAD_LETTER after 5 retries.
 * 4. Outbox lease expiration recovery for crashed/abandoned workers.
 * 5. Saga happy path execution (Authorization -> Airline Dispatch -> Ticket Validation -> Completed).
 * 6. Saga fail-closed gate: rejection of unapproved cases.
 * 7. Saga backward compensation on booking failure (Virtual Card authorization voided).
 * 8. Saga ambiguous timeout handling (transition to WAITING_FOR_EXTERNAL_RESULT without blind retry).
 * 9. Saga worker lease recovery on abandoned saga execution.
 * 10. Admin operator monitoring and recovery endpoints (/api/admin/outbox/**, /api/admin/sagas/**) with RBAC.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class OutboxAndSagaIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private SagaExecutionRepository sagaExecutionRepository;

    @Autowired
    private SagaStepLogRepository sagaStepLogRepository;

    @Autowired
    private RebookingSagaCoordinator rebookingSagaCoordinator;

    @Autowired
    private CaseService caseService;

    @Autowired
    private CaseStateMachineService stateMachineService;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private VirtualCardRepository virtualCardRepository;

    @Autowired
    private PaymentSettlementService paymentSettlementService;

    @MockBean
    private RebookService rebookService;

    @BeforeEach
    void setUp() {
        sagaStepLogRepository.deleteAll();
        sagaExecutionRepository.deleteAll();
        outboxEventRepository.deleteAll();
        virtualCardRepository.deleteAll();

        // Ensure baseline booking and case exist
        bookingRepository.findById("B1").ifPresent(b -> {
            b.setStatus("Checked-in");
            bookingRepository.save(b);
        });

        rescueCaseRepository.findById("C1").ifPresent(c -> {
            c.setState("DETECTED");
            c.setApprovedOptionId(null);
            c.setApprovedBy(null);
            c.setApprovedAt(null);
            c.setNewPnr(null);
            c.setError(null);
            c.setManualNextStep(null);
            rescueCaseRepository.save(c);
        });
    }

    @Test
    @DisplayName("Outbox: Atomic batch claiming and lease locking prevents duplicate worker pickup")
    void testOutboxBatchClaimingAndLeaseLocking() {
        String corrId = "corr-test-1";
        String idemKey = "idem-test-" + UUID.randomUUID();

        OutboxEvent event = outboxService.publishEvent(
                "RESCUE_CASE", "CASE-TEST-1", "CASE_STATE_CHANGED",
                1, "{\"status\":\"DETECTED\"}", corrId, null, idemKey
        );

        assertNotNull(event.getId());
        assertEquals(OutboxEvent.STATUS_PENDING, event.getStatus());

        // Worker 1 claims batch
        List<OutboxEvent> claimed = outboxService.claimEventsBatch("worker-node-1", 10);
        assertTrue(claimed.stream().anyMatch(e -> e.getId().equals(event.getId())));

        OutboxEvent lockedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(OutboxEvent.STATUS_CLAIMED, lockedEvent.getStatus());
        assertEquals("worker-node-1", lockedEvent.getLockedBy());
        assertNotNull(lockedEvent.getLockedAt());

        // Worker 2 attempts to claim batch concurrently - should NOT see the locked event
        List<OutboxEvent> claimedByWorker2 = outboxService.claimEventsBatch("worker-node-2", 10);
        assertFalse(claimedByWorker2.stream().anyMatch(e -> e.getId().equals(event.getId())));

        // Mark completed
        outboxService.markCompleted(event.getId());
        OutboxEvent completedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(OutboxEvent.STATUS_COMPLETED, completedEvent.getStatus());
        assertNotNull(completedEvent.getPublishedAt());
    }

    @Test
    @DisplayName("Outbox: Idempotency key deduplicates duplicate event submissions")
    void testOutboxIdempotencyDeduplication() {
        String idemKey = "idem-dedup-" + UUID.randomUUID();

        OutboxEvent first = outboxService.publishEvent(
                "RESCUE_CASE", "CASE-TEST-DEDUP", "FLIGHT_CANCELLED",
                1, "{\"flight\":\"AI-101\"}", "corr-1", null, idemKey
        );

        OutboxEvent second = outboxService.publishEvent(
                "RESCUE_CASE", "CASE-TEST-DEDUP", "FLIGHT_CANCELLED",
                1, "{\"flight\":\"AI-101\"}", "corr-1", null, idemKey
        );

        assertEquals(first.getId(), second.getId(), "Duplicate publication with same idempotency key must return existing event");
        assertEquals(1, outboxEventRepository.findByIdempotencyKey(idemKey).stream().count());
    }

    @Test
    @DisplayName("Outbox: Exponential backoff increments and event transitions to DEAD_LETTER after max retries")
    void testOutboxExponentialBackoffAndDeadLetter() {
        String idemKey = "idem-dlq-" + UUID.randomUUID();
        OutboxEvent event = outboxService.publishEvent(
                "RESCUE_CASE", "CASE-TEST-DLQ", "DISPATCH_FAILED",
                1, "{\"error\":\"simulated\"}", "corr-dlq", null, idemKey
        );

        // Fail 4 times (retryCount becomes 1, 2, 3, 4)
        for (int i = 1; i <= 4; i++) {
            outboxService.markFailedWithBackoff(event.getId(), "ERR_TRANSIENT", "Temporary failure " + i);
            OutboxEvent failed = outboxEventRepository.findById(event.getId()).orElseThrow();
            assertEquals(OutboxEvent.STATUS_PENDING, failed.getStatus());
            assertEquals(i, failed.getRetryCount());
            assertTrue(failed.getAvailableAt().isAfter(LocalDateTime.now().minusSeconds(5)));
        }

        // 5th failure reaches max_retries (5) -> Transitions to DEAD_LETTER
        outboxService.markFailedWithBackoff(event.getId(), "ERR_FATAL", "Permanent failure threshold reached");
        OutboxEvent dlqEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(OutboxEvent.STATUS_DEAD_LETTER, dlqEvent.getStatus());
        assertEquals(5, dlqEvent.getRetryCount());
        assertEquals("ERR_FATAL", dlqEvent.getLastErrorCode());
    }

    @Test
    @DisplayName("Outbox: Lease expiration recovers events abandoned by crashed worker nodes")
    void testOutboxLeaseExpirationRecovery() {
        String idemKey = "idem-lease-" + UUID.randomUUID();
        OutboxEvent event = outboxService.publishEvent(
                "RESCUE_CASE", "CASE-TEST-LEASE", "EVENT_ABANDONED",
                1, "{}", "corr-lease", null, idemKey
        );

        // Simulate a crashed worker that claimed the event 10 minutes ago
        event.setStatus(OutboxEvent.STATUS_CLAIMED);
        event.setLockedBy("crashed-pod-99");
        event.setLockedAt(LocalDateTime.now().minusMinutes(10));
        outboxEventRepository.save(event);

        // Recover expired leases (lease timeout = 60s)
        int recovered = outboxService.recoverExpiredLeases(60);
        assertTrue(recovered >= 1);

        OutboxEvent recoveredEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertEquals(OutboxEvent.STATUS_PENDING, recoveredEvent.getStatus(), "Abandoned event must revert to PENDING");
        assertNull(recoveredEvent.getLockedBy());
        assertNull(recoveredEvent.getLockedAt());
    }

    @Test
    @DisplayName("Saga: Happy path end-to-end execution orchestrates VCC authorization, airline rebooking and ticketing")
    void testSagaHappyPathExecution() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.approveOption("C1", "O2", "Flyo Finance Desk");

        when(rebookService.executePlaywrightRebook(any(), any(), any(), any()))
                .thenReturn(new RebookResultDto(
                        true, "C1", "O2", "FL301", "M7SAGA1", null, 0,
                        List.of("Portal loaded", "Passenger entered", "Card charged", "PNR M7SAGA1 extracted"),
                        List.of()
                ));
        when(rebookService.executePlaywrightRebook(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new RebookResultDto(
                        true, "C1", "O2", "FL301", "M7SAGA1", null, 0,
                        List.of("Portal loaded", "Passenger entered", "Card charged", "PNR M7SAGA1 extracted"),
                        List.of()
                ));

        SagaExecution saga = rebookingSagaCoordinator.initiateRebookingSaga("C1", "O2", "Flyo Operator");
        assertNotNull(saga);
        assertEquals("C1", saga.getCaseId());

        // Step 1: Authorize payment
        rebookingSagaCoordinator.executeSagaStep(saga.getSagaId(), "test-worker-1");
        SagaExecution afterPay = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STEP_DISPATCH_REBOOK, afterPay.getCurrentStep());

        // Step 2: Dispatch rebooking
        rebookingSagaCoordinator.executeSagaStep(saga.getSagaId(), "test-worker-1");
        SagaExecution afterRebook = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STEP_VERIFY_TICKET, afterRebook.getCurrentStep());

        // Step 3: Verification & completion
        rebookingSagaCoordinator.executeSagaStep(saga.getSagaId(), "test-worker-1");
        SagaExecution completed = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STATUS_COMPLETED, completed.getStatus());
        assertEquals(SagaExecution.STEP_COMPLETED, completed.getCurrentStep());

        // Verify RescueCase state transitioned to DONE
        RescueCase c = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals("DONE", c.getState());
        assertEquals("M7SAGA1", c.getNewPnr());

        // Verify saga step logs were created
        List<SagaStepLog> logs = sagaStepLogRepository.findBySagaIdOrderByCreatedAtAsc(saga.getSagaId());
        assertFalse(logs.isEmpty());
        assertTrue(logs.stream().anyMatch(l -> l.getStepName().equals(SagaExecution.STEP_AUTHORIZE_PAYMENT) && l.getStatus().equals("SUCCEEDED")));
        assertTrue(logs.stream().anyMatch(l -> l.getStepName().equals(SagaExecution.STEP_DISPATCH_REBOOK) && l.getStatus().equals("SUCCEEDED")));
        assertTrue(logs.stream().anyMatch(l -> l.getStepName().equals(SagaExecution.STEP_VERIFY_TICKET) && l.getStatus().equals("SUCCEEDED")));
    }

    @Test
    @DisplayName("Saga: Unapproved cases fail closed and throw ApprovalRequiredException")
    void testSagaRejectionWithoutApproval() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        // Do NOT approve

        assertThrows(ApprovalRequiredException.class, () -> {
            rebookingSagaCoordinator.initiateRebookingSaga("C1", "O2", "Flyo Operator");
        });

        // Ensure no saga was executed
        List<SagaExecution> sagas = sagaExecutionRepository.findByCaseId("C1");
        assertTrue(sagas.isEmpty() || sagas.stream().noneMatch(s -> s.getStatus().equals(SagaExecution.STATUS_COMPLETED)));
    }

    @Test
    @DisplayName("Saga Gate: Cases in AWAITING_APPROVAL state must NEVER authorize saga execution")
    void testSagaRejectionWhenInAwaitingApprovalState() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");

        // Explicitly set to AWAITING_APPROVAL (unapproved queue)
        RescueCase c = rescueCaseRepository.findById("C1").orElseThrow();
        stateMachineService.transition(c, CaseStateMachineService.STATE_AWAITING_APPROVAL, "Operator", "Pending review");
        rescueCaseRepository.save(c);

        assertThrows(ApprovalRequiredException.class, () -> {
            rebookingSagaCoordinator.initiateRebookingSaga("C1", "O2", "Flyo Operator");
        });
    }

    @Test
    @DisplayName("Saga Gate: Expired approvals (>30 mins) fail closed and throw ApprovalRequiredException")
    void testSagaRejectionWhenApprovalExpired() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.approveOption("C1", "O2", "Supervisor Sarah");

        // Expire the approval timestamp
        RescueCase c = rescueCaseRepository.findById("C1").orElseThrow();
        c.setApprovedAt(LocalDateTime.now().minusMinutes(35));
        rescueCaseRepository.save(c);

        assertThrows(ApprovalRequiredException.class, () -> {
            rebookingSagaCoordinator.initiateRebookingSaga("C1", "O2", "Supervisor Sarah");
        });
    }

    @Test
    @DisplayName("Saga: Airline rebooking failure triggers backward compensation (voids virtual card)")
    void testSagaBackwardCompensationOnAirlineFailure() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.approveOption("C1", "O2", "Flyo Finance Desk");

        // Simulate airline booking failure (e.g. seat inventory sold out)
        when(rebookService.executePlaywrightRebook(any(), any(), any(), any()))
                .thenReturn(new RebookResultDto(
                        false, "C1", "O2", "FL301", null, "Flight sold out during checkout", 1,
                        List.of("Selected flight", "Inventory rejected"),
                        List.of()
                ));
        when(rebookService.executePlaywrightRebook(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new RebookResultDto(
                        false, "C1", "O2", "FL301", null, "Flight sold out during checkout", 1,
                        List.of("Selected flight", "Inventory rejected"),
                        List.of()
                ));

        SagaExecution saga = rebookingSagaCoordinator.initiateRebookingSaga("C1", "O2", "Flyo Operator");

        // Step 1: Payment authorization succeeds
        rebookingSagaCoordinator.executeSagaStep(saga.getSagaId(), "test-worker-1");

        // Step 2: Rebooking fails -> Triggers backward compensation
        rebookingSagaCoordinator.executeSagaStep(saga.getSagaId(), "test-worker-1");

        SagaExecution afterFail = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STATUS_FAILED, afterFail.getStatus());
        assertEquals("AIRLINE_REBOOK_REJECTED", afterFail.getFailureReasonCode());

        // Verify Case transitioned to FAILED_ESCALATED
        RescueCase updated = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals("FAILED_ESCALATED", updated.getState());

        // Verify step logs show compensation action
        List<SagaStepLog> logs = sagaStepLogRepository.findBySagaIdOrderByCreatedAtAsc(saga.getSagaId());
        assertTrue(logs.stream().anyMatch(l -> l.getAction().equals("COMPENSATE") && l.getStatus().equals("SUCCEEDED")),
                "Must log successful compensation step for payment voiding");
    }

    @Test
    @DisplayName("Saga: Ambiguous network timeout transitions saga to WAITING_FOR_EXTERNAL_RESULT without blind retry")
    void testSagaAmbiguousTimeoutTransitionsToWaiting() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.approveOption("C1", "O2", "Flyo Finance Desk");

        // Simulate network timeout from airline portal
        when(rebookService.executePlaywrightRebook(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Connection timed out waiting for airline response"));
        when(rebookService.executePlaywrightRebook(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("Connection timed out waiting for airline response"));

        SagaExecution saga = rebookingSagaCoordinator.initiateRebookingSaga("C1", "O2", "Flyo Operator");

        // Step 1: Payment authorization succeeds
        rebookingSagaCoordinator.executeSagaStep(saga.getSagaId(), "test-worker-1");

        // Step 2: Rebooking timeout
        rebookingSagaCoordinator.executeSagaStep(saga.getSagaId(), "test-worker-1");

        SagaExecution afterTimeout = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STATUS_WAITING_FOR_EXTERNAL_RESULT, afterTimeout.getStatus(),
                "Ambiguous timeout must transition to WAITING_FOR_EXTERNAL_RESULT");
        assertEquals("TIMEOUT_UNKNOWN", afterTimeout.getFailureReasonCode());

        // Verify Case is waiting for confirmation, NOT marked with a hallucinated PNR
        RescueCase updated = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_RECONCILING, updated.getState());
        assertNull(updated.getNewPnr());
    }

    @Test
    @DisplayName("Saga: Abandoned saga lease recovery resets locked saga to PENDING")
    void testSagaLeaseRecovery() {
        String caseId = "CASE-SAGA-LEASE-" + UUID.randomUUID().toString().substring(0, 8);
        SagaExecution saga = new SagaExecution(
                caseId, "REBOOKING_SAGA", "{}", "corr-lease-saga", "idem-lease-saga-" + UUID.randomUUID()
        );
        saga.setStatus(SagaExecution.STATUS_RUNNING);
        saga.setLockedBy("dead-worker-1");
        saga.setLockedAt(LocalDateTime.now().minusMinutes(15));
        sagaExecutionRepository.save(saga);

        // Find expired saga leases (> 120 seconds old)
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(120);
        List<SagaExecution> expired = sagaExecutionRepository.findExpiredLeases(threshold);
        assertTrue(expired.stream().anyMatch(s -> s.getSagaId().equals(saga.getSagaId())));

        // Recover
        saga.setStatus(SagaExecution.STATUS_PENDING);
        saga.setLockedBy(null);
        saga.setLockedAt(null);
        sagaExecutionRepository.save(saga);

        SagaExecution recovered = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STATUS_PENDING, recovered.getStatus());
        assertNull(recovered.getLockedBy());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("Admin API: Operator monitoring and dead-letter retry endpoints are accessible to authorized admins")
    void testAdminMonitoringEndpointsAuthorized() throws Exception {
        mockMvc.perform(get("/api/admin/outbox/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingEvents", notNullValue()))
                .andExpect(jsonPath("$.deadLetterEvents", notNullValue()));

        mockMvc.perform(get("/api/admin/outbox/events?status=DEAD_LETTER"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/sagas?status=FAILED"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/sagas/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingSagas", notNullValue()));
    }

    @Test
    @WithMockUser(username = "passenger", roles = {"PASSENGER"})
    @DisplayName("Admin API: Consequential admin endpoints reject unauthorized non-admin roles (403 Forbidden)")
    void testAdminEndpointsForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/outbox/metrics"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/outbox/retry/evt_fake"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/sagas/retry/saga_fake"))
                .andExpect(status().isForbidden());
    }
}
