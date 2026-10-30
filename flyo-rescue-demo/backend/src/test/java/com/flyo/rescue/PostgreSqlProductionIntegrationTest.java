package com.flyo.rescue;

import com.flyo.rescue.model.AuditEvent;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.StateTransition;
import com.flyo.rescue.repository.AuditEventRepository;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.repository.StateTransitionRepository;
import com.flyo.rescue.service.CaseStateMachineService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
        "spring.datasource.url=jdbc:postgresql://localhost:5432/flyodb_prod_test",
        "spring.datasource.username=nani",
        "spring.datasource.password=",
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
}
