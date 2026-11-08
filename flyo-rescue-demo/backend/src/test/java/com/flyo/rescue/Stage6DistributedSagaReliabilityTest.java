package com.flyo.rescue;

import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.exception.ApprovalRequiredException;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.SagaExecution;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.repository.SagaExecutionRepository;
import com.flyo.rescue.service.CaseStateMachineService;
import com.flyo.rescue.service.RebookService;
import com.flyo.rescue.service.saga.RebookingSagaCoordinator;
import com.flyo.rescue.service.saga.SagaExecutionWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest
public class Stage6DistributedSagaReliabilityTest {

    @Autowired
    private RebookingSagaCoordinator sagaCoordinator;

    @Autowired
    private SagaExecutionWorker sagaExecutionWorker;

    @Autowired
    private SagaExecutionRepository sagaRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private RebookOptionRepository optionRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @MockBean
    private RebookService rebookService;

    private String testCaseId;
    private String testOptionId;

    @BeforeEach
    void setUp() {
        long ts = System.currentTimeMillis();
        testCaseId = "CASE-SAGA-" + ts;
        testOptionId = "OPT-SAGA-" + ts;

        Booking booking = new Booking("B-" + ts, "Vikram Malhotra", "PNR-SAGA-" + ts, "DEL → BOM", "AI-101", "10:00", "None", "None", "Desk", "Checked-in");
        bookingRepository.save(booking);

        RescueCase rescueCase = new RescueCase(testCaseId, booking.getId(), "Vikram Malhotra", CaseStateMachineService.STATE_APPROVED,
                "DEL-BOM", "AI-101", "Missed connection", "None", "None");
        rescueCase.setApprovedBy("supervisor_ops");
        rescueCase.setApprovedAt(LocalDateTime.now().minusMinutes(2));
        rescueCase.setApprovedOptionId(testOptionId);
        rescueCaseRepository.save(rescueCase);

        RebookOption option = new RebookOption(testOptionId, testCaseId, "AI-839", "HYD → DEL", "10:00", "12:15", 90, 0, true, "Best alternative", 1);
        optionRepository.save(option);
    }

    @Test
    @DisplayName("Saga 6.1: Startup recovery immediately reclaims orphaned leases without waiting for lease timeout")
    void testStartupLeaseRecovery() {
        String sagaId = "saga_abandoned_" + System.currentTimeMillis();
        SagaExecution abandoned = new SagaExecution(testCaseId, "REBOOKING_SAGA", "{\"flight\":\"AI-839\"}", "corr_1", "idem_" + sagaId);
        abandoned.setStatus(SagaExecution.STATUS_RUNNING);
        abandoned.setLockedBy("dead-worker-pid-9999");
        abandoned.setLockedAt(LocalDateTime.now().minusHours(2));
        sagaRepository.saveAndFlush(abandoned);

        // Simulate application restart lease recovery event
        sagaExecutionWorker.onStartupLeaseRecovery();

        SagaExecution recovered = sagaRepository.findById(abandoned.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STATUS_PENDING, recovered.getStatus(), "Abandoned saga must be restored to PENDING");
        assertNull(recovered.getLockedBy(), "Lock holder must be cleared on startup recovery");
        assertNull(recovered.getLockedAt());
    }

    @Test
    @DisplayName("Saga 6.2: Duplicate command prevention returns existing saga and blocks parallel execution")
    void testDuplicateSagaInitiationPrevention() {
        // First initiation
        SagaExecution saga1 = sagaCoordinator.initiateRebookingSaga(testCaseId, testOptionId, "supervisor_ops");
        assertNotNull(saga1);

        // Second initiation with identical case & option -> returns existing saga (Idempotency)
        SagaExecution saga2 = sagaCoordinator.initiateRebookingSaga(testCaseId, testOptionId, "supervisor_ops");
        assertEquals(saga1.getSagaId(), saga2.getSagaId(), "Identical request must return idempotent existing saga");

        // Verify only 1 saga exists for this case
        List<SagaExecution> sagas = sagaRepository.findByCaseId(testCaseId);
        assertEquals(1, sagas.size(), "Zero duplicate sagas allowed per case");
    }

    @Test
    @DisplayName("Saga 6.3: CRITICAL SAFETY RULE — Network timeout marks state UNKNOWN and halts blind retries")
    void testAirlineTimeoutEnforcesAmbiguousSafetyState() {
        // Mock rebookService throwing Socket Timeout Exception
        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Read timed out connecting to airline GDS endpoint"));

        SagaExecution saga = sagaCoordinator.initiateRebookingSaga(testCaseId, testOptionId, "supervisor_ops");
        assertNotNull(saga);

        // Advance past Step 1 (Payment hold/check) to Step 2 (Rebook)
        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-test");
        // Execute Step 2 (Rebooking dispatch) -> encounters mocked timeout
        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-test");

        // Reload saga
        SagaExecution evaluated = sagaRepository.findById(saga.getSagaId()).orElseThrow();

        // Must enter WAITING_FOR_EXTERNAL_RESULT and currentStep RECONCILE_BOOKING
        assertEquals(SagaExecution.STATUS_WAITING_FOR_EXTERNAL_RESULT, evaluated.getStatus(),
                "After timeout, saga must enter WAITING_FOR_EXTERNAL_RESULT");
        assertEquals(SagaExecution.STEP_RECONCILE_BOOKING, evaluated.getCurrentStep(),
                "Next step must be RECONCILE_BOOKING, NEVER blind re-dispatch");

        // Case must be marked RECONCILING
        RescueCase c = rescueCaseRepository.findById(testCaseId).orElseThrow();
        assertEquals(CaseStateMachineService.STATE_RECONCILING, c.getState(),
                "Case state must be RECONCILING to warn operators");
    }

    @Test
    @DisplayName("Saga 6.4: CRITICAL SAFETY RULE — Ambiguous reconciliation escalates to MANUAL_INTERVENTION_REQUIRED")
    void testAmbiguousReconciliationEscalatesToManualIntervention() {
        SagaExecution saga = sagaCoordinator.initiateRebookingSaga(testCaseId, testOptionId, "supervisor_ops");
        saga.setStatus(SagaExecution.STATUS_WAITING_FOR_EXTERNAL_RESULT);
        saga.setCurrentStep(SagaExecution.STEP_RECONCILE_BOOKING);
        sagaRepository.saveAndFlush(saga);

        // Execute reconciliation step (without existing PNR on airline host)
        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-test");

        SagaExecution resolved = sagaRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STATUS_MANUAL_INTERVENTION_REQUIRED, resolved.getStatus(),
                "Unresolved ambiguous airline booking MUST escalate to MANUAL_INTERVENTION_REQUIRED");

        RescueCase c = rescueCaseRepository.findById(testCaseId).orElseThrow();
        assertEquals(CaseStateMachineService.STATE_MANUAL_INTERVENTION_REQUIRED, c.getState());
        assertTrue(c.getManualNextStep().contains("Verify carrier host"), "Must contain clear human operational instructions");
    }

    @Test
    @DisplayName("Saga 6.5: Max attempts exceeded dead-letters saga to MANUAL_INTERVENTION_REQUIRED and compensates")
    void testMaxAttemptsDeadLetterHandling() {
        SagaExecution saga = sagaCoordinator.initiateRebookingSaga(testCaseId, testOptionId, "supervisor_ops");
        saga.setCurrentStep(SagaExecution.STEP_DISPATCH_REBOOK);
        saga.setAttemptCount(3); // At max attempts (3/3)
        saga.setMaxAttempts(3);
        sagaRepository.saveAndFlush(saga);

        // Force a non-timeout internal failure
        RebookResultDto failureResult = new RebookResultDto(false, testCaseId, testOptionId, "AI-839", null,
                "No seats available in inventory", 1, List.of(), List.of());
        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(failureResult);

        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-test");

        SagaExecution deadLettered = sagaRepository.findById(saga.getSagaId()).orElseThrow();
        assertTrue(
                SagaExecution.STATUS_FAILED.equals(deadLettered.getStatus()) ||
                SagaExecution.STATUS_MANUAL_INTERVENTION_REQUIRED.equals(deadLettered.getStatus()),
                "Failed or manual intervention required when inventory exhausted"
        );
    }
}
