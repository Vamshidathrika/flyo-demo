package com.flyo.rescue;

import com.flyo.rescue.config.ProductionSecurityValidator;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.SagaExecution;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.PaymentTransactionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.repository.SagaExecutionRepository;
import com.flyo.rescue.service.PaymentSettlementService;
import com.flyo.rescue.service.saga.RebookingSagaCoordinator;
import com.flyo.rescue.service.saga.SagaExecutionWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class Stage6ChaosAndFailureInjectionTest {

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private SagaExecutionRepository sagaExecutionRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private com.flyo.rescue.repository.RebookOptionRepository optionRepository;

    @Autowired
    private RebookingSagaCoordinator sagaCoordinator;

    @Autowired
    private SagaExecutionWorker sagaWorker;

    @Autowired
    private PaymentSettlementService paymentService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Chaos 1: Airline API timeout preserves ambiguous state and prevents duplicate bookings")
    void testChaosAirlineApiTimeoutSafety() {
        String bookingId = "B-CHAOS-1-" + System.currentTimeMillis();
        Booking b = new Booking(bookingId, "Timeout Pax", "PNRCHAOS1", "DEL → BOM", "AI-101", "10:00", "None", "None", "Desk", "Checked-in");
        bookingRepository.save(b);

        String caseId = "CHAOS-CASE-TIMEOUT-" + System.currentTimeMillis();
        RescueCase c = new RescueCase(caseId, bookingId, "Timeout Pax", "APPROVED", "DEL-BOM", "AI-101", "Delay", "None", "None");
        c.setApprovedOptionId("OPT-CHAOS-1");
        c.setApprovedBy("supervisor_chaos");
        c.setApprovedAt(LocalDateTime.now().minusMinutes(2));
        rescueCaseRepository.saveAndFlush(c);

        com.flyo.rescue.model.RebookOption opt = new com.flyo.rescue.model.RebookOption(
                "OPT-CHAOS-1", caseId, "AI-101", "DEL → BOM", "10:00", "12:00", 60, 0, true, "Best alternative", 1);
        optionRepository.save(opt);

        SagaExecution saga = sagaCoordinator.initiateRebookingSaga(caseId, "OPT-CHAOS-1", "supervisor_chaos");
        assertNotNull(saga);

        // Advance step
        sagaCoordinator.executeSagaStep(saga.getSagaId(), "worker-chaos");

        // Reload latest version to prevent optimistic locking race
        SagaExecution currentSaga = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();

        // Simulate Airline API Timeout failure -> triggers ambiguous outcome handling
        sagaCoordinator.handleAmbiguousOutcome(currentSaga, "TIMEOUT_UNKNOWN", "Read timed out from Amadeus GDS");

        SagaExecution evaluated = sagaExecutionRepository.findById(saga.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STATUS_WAITING_FOR_EXTERNAL_RESULT, evaluated.getStatus());
        assertEquals(SagaExecution.STEP_RECONCILE_BOOKING, evaluated.getCurrentStep());

        // Ensure attempting to start another saga for this case in RECONCILING state is strictly BLOCKED
        assertThrows(com.flyo.rescue.exception.ApprovalRequiredException.class, () ->
                sagaCoordinator.initiateRebookingSaga(caseId, "OPT-CHAOS-1", "supervisor_chaos"));
    }

    @Test
    @DisplayName("Chaos 2: Duplicate booking requests are strictly rejected via saga idempotency")
    void testChaosDuplicateBookingPrevention() {
        String bookingId = "B-CHAOS-2-" + System.currentTimeMillis();
        Booking b = new Booking(bookingId, "Dup Pax", "PNRCHAOS2", "DEL → BOM", "AI-102", "10:00", "None", "None", "Desk", "Checked-in");
        bookingRepository.save(b);

        String caseId = "CHAOS-CASE-DUP-" + System.currentTimeMillis();
        RescueCase c = new RescueCase(caseId, bookingId, "Dup Pax", "APPROVED", "DEL-BOM", "AI-102", "Delay", "None", "None");
        c.setApprovedOptionId("OPT-CHAOS-2");
        c.setApprovedBy("supervisor_1");
        c.setApprovedAt(LocalDateTime.now().minusMinutes(2));
        rescueCaseRepository.saveAndFlush(c);

        com.flyo.rescue.model.RebookOption opt = new com.flyo.rescue.model.RebookOption(
                "OPT-CHAOS-2", caseId, "AI-102", "DEL → BOM", "10:00", "12:00", 60, 0, true, "Best alternative", 1);
        optionRepository.save(opt);

        SagaExecution first = sagaCoordinator.initiateRebookingSaga(caseId, "OPT-CHAOS-2", "supervisor_1");
        assertNotNull(first);

        SagaExecution second = sagaCoordinator.initiateRebookingSaga(caseId, "OPT-CHAOS-2", "supervisor_2");
        assertEquals(first.getSagaId(), second.getSagaId(), "Concurrent/duplicate saga request must return existing saga");

        List<SagaExecution> sagas = sagaExecutionRepository.findByCaseId(caseId);
        assertEquals(1, sagas.size(), "Database must strictly contain exactly 1 saga execution record");
    }

    @Test
    @DisplayName("Chaos 3: Database failure triggers atomic transaction rollback without partial state leakage")
    void testChaosDatabaseTransactionRollback() {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        String caseId = "CHAOS-ROLLBACK-" + System.currentTimeMillis();

        assertThrows(RuntimeException.class, () -> {
            tt.execute(status -> {
                RescueCase c = new RescueCase(caseId, "B-ROLLBACK", "Rollback Pax", "DETECTED", "DEL-BOM", "AI-103", "Delay", "None", "None");
                rescueCaseRepository.save(c);
                throw new RuntimeException("Simulated mid-transaction catastrophic database/network failure");
            });
        });

        // Verify case was rolled back completely
        Optional<RescueCase> found = rescueCaseRepository.findById(caseId);
        assertTrue(found.isEmpty(), "Transaction rollback must guarantee no dirty orphan record was committed");
    }

    @Test
    @DisplayName("Chaos 4: Worker recovery reclaims abandoned saga leases after simulated node restart")
    void testChaosWorkerRecoveryAfterRestart() {
        String caseId = "CHAOS-RESTART-" + System.currentTimeMillis();
        SagaExecution orphan = new SagaExecution(
                "SAGA-ORPHAN-" + System.currentTimeMillis(),
                caseId,
                "tenant_default",
                "OPT-ORPHAN",
                "supervisor_restart"
        );
        orphan.setStatus(SagaExecution.STATUS_RUNNING);
        orphan.setLockedBy("dead-worker-pod-xyz");
        orphan.setLockedAt(LocalDateTime.now().minusHours(2)); // Expired lease
        sagaExecutionRepository.saveAndFlush(orphan);

        sagaWorker.onStartupLeaseRecovery();

        SagaExecution refreshed = sagaExecutionRepository.findById(orphan.getSagaId()).orElseThrow();
        assertEquals(SagaExecution.STATUS_PENDING, refreshed.getStatus());
        assertNull(refreshed.getLockedBy());
    }

    @Test
    @DisplayName("Chaos 5: Payment idempotency key prevents duplicate settlement on duplicate webhooks")
    void testChaosPaymentWebhookIdempotency() {
        String idempotencyKey = "PAY-IDEMP-" + System.currentTimeMillis();
        String caseId = "CASE-PAY-CHAOS-" + System.currentTimeMillis();

        PaymentTransaction first = paymentService.authorizePayment(
                caseId, "OPT-1", 5000, "INR", "GDS Airline Settlement", idempotencyKey);
        assertNotNull(first);
        assertEquals(idempotencyKey, first.getIdempotencyKey());

        // Resend identical payment command with same idempotency key
        PaymentTransaction second = paymentService.authorizePayment(
                caseId, "OPT-1", 5000, "INR", "GDS Airline Settlement", idempotencyKey);

        assertEquals(first.getId(), second.getId(), "Duplicate payment submission must return existing idempotent transaction");
    }

    @Test
    @DisplayName("Chaos 6: Concurrent supervisor approvals trigger optimistic locking failure on stale state")
    void testChaosConcurrentSupervisorApproval() {
        String bookingId = "B-CONCUR-" + System.currentTimeMillis();
        Booking b = new Booking(bookingId, "Concurrent Pax", "PNRCONCUR", "DEL → BOM", "AI-104", "10:00", "None", "None", "Desk", "Checked-in");
        bookingRepository.save(b);

        String caseId = "CASE-CONCURRENT-" + System.currentTimeMillis();
        RescueCase c = new RescueCase(caseId, bookingId, "Concurrent Pax", "DETECTED", "DEL-BOM", "AI-104", "Delay", "None", "None");
        rescueCaseRepository.saveAndFlush(c);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // Worker 1 loads case (version = 0)
        RescueCase worker1Case = rescueCaseRepository.findById(caseId).orElseThrow();
        assertEquals(0L, worker1Case.getVersion());

        // Worker 2 prepares stale update with version 0
        RescueCase worker2Case = new RescueCase(worker1Case.getId(), worker1Case.getBookingId(), worker1Case.getPassengerName(),
                worker1Case.getState(), worker1Case.getCancelledSegment(), worker1Case.getFlight(),
                worker1Case.getImpact(), worker1Case.getMissedConnection(), worker1Case.getHotelRisk());
        worker2Case.setVersion(0L); // Stale version 0

        // Worker 1 updates and saves successfully -> version increments to 1
        txTemplate.execute(status -> {
            worker1Case.setState("APPROVED");
            rescueCaseRepository.saveAndFlush(worker1Case);
            return null;
        });

        RescueCase afterWorker1 = rescueCaseRepository.findById(caseId).orElseThrow();
        assertEquals(1L, afterWorker1.getVersion(), "Version must increment after successful commit");

        // Worker 2 attempts to save with stale version 0 -> MUST throw optimistic locking failure
        worker2Case.setState("REJECTED");
        assertThrows(ObjectOptimisticLockingFailureException.class, () ->
                rescueCaseRepository.saveAndFlush(worker2Case));
    }

    @Test
    @DisplayName("Chaos 7: Production environment validator strictly fails closed on missing PostgreSQL")
    void testChaosProductionFailClosedWithoutPostgres() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.datasource.url", "jdbc:h2:mem:inmemory_forbidden");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        ReflectionTestUtils.setField(validator, "jwtSecret", "a_very_long_valid_jwt_secret_key_32chars_min");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                validator.onApplicationEvent(null));
        assertTrue(ex.getMessage().contains("PostgreSQL"));
    }
}
