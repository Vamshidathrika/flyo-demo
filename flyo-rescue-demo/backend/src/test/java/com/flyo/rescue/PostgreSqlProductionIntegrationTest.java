package com.flyo.rescue;

import com.flyo.rescue.model.*;
import com.flyo.rescue.repository.*;
import com.flyo.rescue.service.CaseStateMachineService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STEP 3 VERIFICATION: Real PostgreSQL Database Integration Test.
 *
 * Runs against an actual PostgreSQL 14+ database instance.
 * Verifies Flyway migrations, CRUD, transaction rollback, foreign key cascades,
 * optimistic locking (@Version), and persistence across restarts.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:${POSTGRES_URL:jdbc:postgresql://localhost:5432/flyodb_prod_test}}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:${POSTGRES_USER:nani}}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:${POSTGRES_PASSWORD:}}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true"
})
public class PostgreSqlProductionIntegrationTest {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private StateTransitionRepository stateTransitionRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private CaseStateMachineService stateMachineService;

    @Autowired
    private SagaExecutionRepository sagaExecutionRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("PostgreSQL Step 3.1: CRUD operations and Flyway validation on real PostgreSQL")
    void testRealPostgreSqlCrud() {
        String testBookingId = "B-PG-" + System.currentTimeMillis();
        Booking booking = new Booking(
                testBookingId,
                "Aditi Rao",
                "PNRPG9",
                "HYD → DEL",
                "AI-839",
                "Tonight 21:00",
                "DEL → LHR (BA-142)",
                "The Claridges New Delhi",
                "Corporate Desk",
                "Checked-in"
        );
        Booking saved = bookingRepository.save(booking);
        assertNotNull(saved);
        assertEquals("Aditi Rao", saved.getPassengerName());

        RescueCase c = new RescueCase(
                "CASE-" + testBookingId,
                testBookingId,
                saved.getPassengerName(),
                "DETECTED",
                saved.getRoute(),
                saved.getFlight(),
                "Onward flight missed",
                saved.getOnwardFlight(),
                saved.getHotelNote()
        );
        RescueCase savedCase = rescueCaseRepository.save(c);
        assertNotNull(savedCase);
        assertEquals(0L, savedCase.getVersion(), "Initial optimistic lock version must be 0");

        // State Machine transition on real PostgreSQL
        stateMachineService.transition(savedCase, CaseStateMachineService.STATE_OPTIONS_READY, "Flyo Engine", "Calculated options on PostgreSQL");
        rescueCaseRepository.save(savedCase);

        RescueCase reloaded = rescueCaseRepository.findById(savedCase.getId()).orElseThrow();
        assertEquals("OPTIONS_READY", reloaded.getState());

        List<StateTransition> transitions = stateTransitionRepository.findByCaseIdOrderByTimestampAsc(savedCase.getId());
        assertFalse(transitions.isEmpty(), "State transition must be written to PostgreSQL state_transitions table");
    }

    @Test
    @DisplayName("PostgreSQL Step 3.2: Transaction Rollback on failure leaves zero partial state")
    void testRealPostgreSqlTransactionRollback() {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName("TestRollbackTx");
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        TransactionStatus status = transactionManager.getTransaction(def);

        String doomedBookingId = "B-DOOMED-" + System.currentTimeMillis();
        Booking doomed = new Booking(doomedBookingId, "Rollback Pax", "DOOMED", "DEL → BOM", "AI-101", "10:00", "None", "None", "Desk", "Checked-in");
        bookingRepository.save(doomed);

        // Force explicit rollback
        transactionManager.rollback(status);

        // Assert booking does NOT exist in real PostgreSQL
        assertFalse(bookingRepository.findById(doomedBookingId).isPresent(), "Rolled back transaction must not persist to PostgreSQL");
    }

    @Test
    @DisplayName("PostgreSQL Step 3.3: Persistence across service restart / EntityManager clear")
    void testPersistenceAcrossSessionRestart() {
        String testId = "B-RESTART-" + System.currentTimeMillis();
        Booking booking = new Booking(testId, "Persistent Pax", "PERSPNR", "BLR → DEL", "6E-501", "14:00", "None", "None", "Desk", "Checked-in");
        bookingRepository.save(booking);

        AuditEvent event = new AuditEvent("CASE-" + testId, "BOOKING_CREATED", "System", "Persisted to PostgreSQL");
        auditEventRepository.save(event);

        // Simulate application restart: clear JPA level-1 cache
        entityManager.clear();

        // Retrieve directly from database
        Booking reloaded = bookingRepository.findById(testId).orElseThrow();
        assertEquals("Persistent Pax", reloaded.getPassengerName());

        List<AuditEvent> auditHistory = auditEventRepository.findByCaseIdOrderByTimestampAsc("CASE-" + testId);
        assertFalse(auditHistory.isEmpty(), "Audit history must persist across session boundary");
        assertEquals("BOOKING_CREATED", auditHistory.get(0).getEvent());
    }

    @Test
    @DisplayName("PostgreSQL Step 3.4: Foreign Key constraints and cascade deletes")
    void testForeignKeyConstraintsOnPostgreSql() {
        String testId = "B-CASCADE-" + System.currentTimeMillis();
        Booking booking = new Booking(testId, "Cascade Pax", "CASCPNR", "MAA → DEL", "AI-502", "16:00", "None", "None", "Desk", "Checked-in");
        bookingRepository.save(booking);

        RescueCase c = new RescueCase("CASE-" + testId, testId, "Cascade Pax", "DETECTED", "MAA-DEL", "AI-502", "Impact", "None", "None");
        rescueCaseRepository.save(c);

        assertTrue(rescueCaseRepository.findById("CASE-" + testId).isPresent());

        // Delete parent booking -> cascades delete to rescue_cases on PostgreSQL
        bookingRepository.delete(booking);

        assertFalse(bookingRepository.findById(testId).isPresent());
        assertFalse(rescueCaseRepository.findById("CASE-" + testId).isPresent(), "Deleting parent booking must cascade delete child rescue case");
    }

    @Test
    @DisplayName("PostgreSQL Step 3.5: Optimistic locking rejects concurrent case updates with stale version")
    void testOptimisticLockingConcurrentCaseUpdate() {
        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);

        String testId = "B-OPT-" + System.currentTimeMillis();
        txTemplate.execute(status -> {
            Booking booking = new Booking(testId, "Optimistic Pax", "OPTPNR", "BOM → DEL", "AI-102", "18:00", "None", "None", "Desk", "Checked-in");
            bookingRepository.save(booking);

            RescueCase originalCase = new RescueCase("CASE-" + testId, testId, "Optimistic Pax", "DETECTED", "BOM-DEL", "AI-102", "Impact", "None", "None");
            rescueCaseRepository.save(originalCase);
            return null;
        });

        // Worker 1 loads case (version = 0)
        RescueCase worker1Case = rescueCaseRepository.findById("CASE-" + testId).orElseThrow();
        assertEquals(0L, worker1Case.getVersion());

        // Worker 2 prepares stale update with version 0
        RescueCase worker2Case = new RescueCase(worker1Case.getId(), worker1Case.getBookingId(), worker1Case.getPassengerName(),
                worker1Case.getState(), worker1Case.getCancelledSegment(), worker1Case.getFlight(),
                worker1Case.getImpact(), worker1Case.getMissedConnection(), worker1Case.getHotelRisk());
        worker2Case.setVersion(0L); // Stale version 0

        // Worker 1 updates and saves successfully -> version increments to 1
        txTemplate.execute(status -> {
            worker1Case.setState("OPTIONS_READY");
            rescueCaseRepository.save(worker1Case);
            return null;
        });

        RescueCase afterWorker1 = rescueCaseRepository.findById("CASE-" + testId).orElseThrow();
        assertEquals(1L, afterWorker1.getVersion(), "Version must increment after successful commit");

        // Worker 2 attempts to save with stale version 0 -> MUST throw optimistic locking failure
        worker2Case.setState("AWAITING_APPROVAL");
        assertThrows(Exception.class, () -> {
            txTemplate.execute(status -> {
                rescueCaseRepository.save(worker2Case);
                return null;
            });
        }, "Concurrent update with stale version must trigger optimistic locking failure");
    }

    @Test
    @DisplayName("PostgreSQL Step 3.6: Durable saga state & payment ledger persistence with optimistic locking")
    void testDurableSagaAndPaymentPersistenceWithOptimisticLocking() {
        String sagaId = "saga_pg_" + System.currentTimeMillis();
        String caseId = "CASE_SAGA_" + System.currentTimeMillis();
        String idemKey = "idem_pg_" + sagaId;

        SagaExecution saga = new SagaExecution(caseId, "REBOOKING_SAGA", "{\"flight\":\"AI-839\"}", "corr_pg_1", idemKey);
        saga.setTenantId("tenant_corporate_a");
        SagaExecution savedSaga = sagaExecutionRepository.saveAndFlush(saga);

        assertNotNull(savedSaga);
        assertEquals(0L, savedSaga.getVersion(), "Initial saga version must be 0");
        assertEquals(SagaExecution.STATUS_PENDING, savedSaga.getStatus());

        // Update saga state
        savedSaga.setStatus(SagaExecution.STATUS_RUNNING);
        savedSaga.setCurrentStep(SagaExecution.STEP_DISPATCH_REBOOK);
        sagaExecutionRepository.saveAndFlush(savedSaga);

        SagaExecution reloadedSaga = sagaExecutionRepository.findById(savedSaga.getSagaId()).orElseThrow();
        assertEquals(1L, reloadedSaga.getVersion(), "Saga version must increment to 1");
        assertEquals(SagaExecution.STATUS_RUNNING, reloadedSaga.getStatus());

        // Test PaymentTransaction durable persistence
        String txnId = "TXN_PG_" + System.currentTimeMillis();
        PaymentTransaction txn = new PaymentTransaction(
                txnId,
                caseId,
                "tenant_corporate_a",
                "card_vcc_pg",
                5500,
                "INR",
                "Air India GDS",
                "AUTHORIZED",
                "REF_AMADEUS_123",
                LocalDateTime.now()
        );
        txn.setIdempotencyKey("idem_pay_" + txnId);
        txn.setTransactionType(PaymentTransaction.TYPE_AUTH);
        PaymentTransaction savedTxn = paymentTransactionRepository.saveAndFlush(txn);

        assertNotNull(savedTxn);
        assertEquals(0L, savedTxn.getVersion(), "Payment version must be initialized to 0");
        assertEquals("INR", savedTxn.getCurrency());
        assertEquals("AUTHORIZED", savedTxn.getStatus());

        // Capture payment
        savedTxn.setStatus("CAPTURED");
        savedTxn.setTransactionType(PaymentTransaction.TYPE_CAPTURE);
        savedTxn.setReconciled(true);
        savedTxn.setReconciledAt(LocalDateTime.now());
        paymentTransactionRepository.saveAndFlush(savedTxn);

        PaymentTransaction reloadedTxn = paymentTransactionRepository.findById(txnId).orElseThrow();
        assertEquals(1L, reloadedTxn.getVersion(), "Payment transaction version must increment to 1");
        assertTrue(reloadedTxn.getReconciled());
    }

    @Test
    @DisplayName("PostgreSQL Step 3.7: Multi-tenant database isolation prevents cross-tenant data leakage")
    void testTenantIsolationOnPostgreSql() {
        String caseIdA = "CASE-TENANT-A-" + System.currentTimeMillis();
        String caseIdB = "CASE-TENANT-B-" + System.currentTimeMillis();

        RescueCase caseA = new RescueCase(caseIdA, "B1", "Pax A", "DETECTED", "DEL-BOM", "AI-101", "Delay", "None", "None");
        caseA.setTenantId("tenant_airline_alpha");
        rescueCaseRepository.saveAndFlush(caseA);

        RescueCase caseB = new RescueCase(caseIdB, "B2", "Pax B", "DETECTED", "DEL-BLR", "6E-202", "Delay", "None", "None");
        caseB.setTenantId("tenant_airline_beta");
        rescueCaseRepository.saveAndFlush(caseB);

        List<RescueCase> alphaCases = rescueCaseRepository.findByTenantId("tenant_airline_alpha");
        assertTrue(alphaCases.stream().anyMatch(c -> c.getId().equals(caseIdA)));
        assertFalse(alphaCases.stream().anyMatch(c -> c.getId().equals(caseIdB)), "Tenant Alpha must NEVER see Tenant Beta cases");

        List<RescueCase> betaCases = rescueCaseRepository.findByTenantId("tenant_airline_beta");
        assertTrue(betaCases.stream().anyMatch(c -> c.getId().equals(caseIdB)));
        assertFalse(betaCases.stream().anyMatch(c -> c.getId().equals(caseIdA)), "Tenant Beta must NEVER see Tenant Alpha cases");
    }
}
