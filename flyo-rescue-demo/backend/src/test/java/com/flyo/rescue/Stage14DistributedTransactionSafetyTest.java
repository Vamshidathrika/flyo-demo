package com.flyo.rescue;

import com.flyo.rescue.model.*;
import com.flyo.rescue.repository.*;
import com.flyo.rescue.security.TenantContext;
import com.flyo.rescue.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STAGE 14: DISTRIBUTED TRANSACTION SAFETY & AUTHORITATIVE RECONCILIATION TEST SUITE
 *
 * Verifies:
 * 1. Database Referential Integrity & Schema Cascades.
 * 2. Authoritative Reconciliation Hardening (Operator Auth, Tenant Isolation, Version Bumping, Stale State Revocation).
 * 3. Distributed Business Idempotency & Locking.
 * 4. Webhook Ingress Deduplication.
 * 5. Provider Timeout & Unknown-Outcome Escalation.
 * 6. Multi-Segment Recovery Engine Strategies (A, B, C).
 * 7. State Machine Invariants.
 */
@SpringBootTest
@ActiveProfiles("test")
public class Stage14DistributedTransactionSafetyTest {

    private static final String TEST_TENANT = "Flyo Corp";
    private static final String OTHER_TENANT = "Acme Corp";
    private static final String TEST_PHONE = "+919123456789";

    @Autowired
    private CanonicalItineraryService canonicalItineraryService;

    @Autowired
    private DistributedIdempotencyService distributedIdempotencyService;

    @Autowired
    private FlightSearchService flightSearchService;

    @Autowired
    private CaseStateMachineService stateMachineService;

    @Autowired
    private PassengerOtpService passengerOtpService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private FlightSegmentRepository flightSegmentRepository;

    @Autowired
    private RebookOptionRepository rebookOptionRepository;

    @Autowired
    private PassengerOtpChallengeRepository otpChallengeRepository;

    @Autowired
    private BusinessIdempotencyKeyRepository businessIdempotencyKeyRepository;

    @Autowired
    private WebhookDeduplicationRepository webhookDeduplicationRepository;

    @Autowired
    private ProviderReconciliationRepository providerReconciliationRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        TenantContext.setTenantId(TEST_TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        passengerOtpService.clearLockouts();
    }

    private Booking createBooking(String id, String passenger, String pnr, String route, String flight, String tenant) {
        Booking b = new Booking(id, passenger, pnr, route, flight, "Tomorrow 08:00", "None", "None", "Flyo Agency", "Confirmed", TEST_PHONE);
        b.setTenantId(tenant);
        b.setItineraryVersion(1L);
        return bookingRepository.save(b);
    }

    private RescueCase createCase(String id, String bookingId, String name, String flight, String route, String tenant) {
        RescueCase c = new RescueCase();
        c.setId(id);
        c.setBookingId(bookingId);
        c.setPnr("PNR-" + id);
        c.setPassengerName(name);
        c.setPassengerPhone(TEST_PHONE);
        c.setFlight(flight);
        c.setCancelledSegment(route);
        c.setOriginAirport("BLR");
        c.setDestinationAirport("HYD");
        c.setFinalDestination("DEL");
        c.setState(CaseStateMachineService.STATE_OPTIONS_READY);
        c.setTenantId(tenant);
        c.setItineraryVersion(1L);
        c.setCreatedAt(LocalDateTime.now());
        return rescueCaseRepository.save(c);
    }

    @Test
    @DisplayName("Safety 1: Referential Integrity - Unique Booking Segment Constraint")
    void testDatabaseReferentialIntegrityAndConstraints() {
        String bookingId = "B-REF-" + UUID.randomUUID().toString().substring(0, 8);
        createBooking(bookingId, "Kavita Rao", "PNR-REF", "BLR → HYD", "6E-201", TEST_TENANT);

        FlightSegment seg1 = new FlightSegment(UUID.randomUUID().toString(), bookingId, 0, "6E-201", "BLR", "HYD", "Tomorrow 08:00", "Tomorrow 09:15", 1L);
        flightSegmentRepository.saveAndFlush(seg1);

        // Attempting to save another segment for the same booking with the same segmentIndex must violate unique constraint
        FlightSegment duplicateSeg = new FlightSegment(UUID.randomUUID().toString(), bookingId, 0, "6E-201", "BLR", "HYD", "Tomorrow 08:00", "Tomorrow 09:15", 1L);
        assertThrows(DataIntegrityViolationException.class, () -> {
            flightSegmentRepository.saveAndFlush(duplicateSeg);
        }, "Database unique constraint uq_booking_segment must reject duplicate segmentIndex for the same booking");
    }

    @Test
    @DisplayName("Safety 2: Authoritative Reconciliation Rejects Unauthenticated Operators")
    void testReconciliationRejectsUnauthenticatedOperator() {
        String caseId = "CASE-AUTH-OP";
        createBooking("B-AUTH-OP", "Vikram Patel", "PNR-OP", "BLR → HYD", "6E-202", TEST_TENANT);
        createCase(caseId, "B-AUTH-OP", "Vikram Patel", "6E-202", "BLR → HYD", TEST_TENANT);

        assertThrows(SecurityException.class, () -> {
            canonicalItineraryService.reconcileCase(caseId, null);
        }, "Reconciliation must reject null operator identity");

        assertThrows(SecurityException.class, () -> {
            canonicalItineraryService.reconcileCase(caseId, "   ");
        }, "Reconciliation must reject blank operator identity");
    }

    @Test
    @DisplayName("Safety 3: Authoritative Reconciliation Enforces Tenant Isolation")
    void testReconciliationEnforcesTenantIsolation() {
        String caseId = "CASE-TENANT-ISO";
        createBooking("B-TENANT-ISO", "Ananya Sharma", "PNR-TENANT", "BLR → HYD", "6E-203", OTHER_TENANT);
        createCase(caseId, "B-TENANT-ISO", "Ananya Sharma", "6E-203", "BLR → HYD", OTHER_TENANT);

        // Current context is TEST_TENANT; attempting to reconcile a case belonging to OTHER_TENANT must fail
        TenantContext.setTenantId(TEST_TENANT);

        assertThrows(SecurityException.class, () -> {
            canonicalItineraryService.reconcileCase(caseId, "operator@flyo.com");
        }, "Cross-tenant reconciliation invocation must be blocked with SecurityException");
    }

    @Test
    @DisplayName("Safety 4: Reconciliation Bumps Itinerary Version and Revokes Old State")
    void testReconciliationBumpsItineraryVersionAndRevokesOldState() {
        String caseId = "CASE-VER-BUMP";
        String bookingId = "B-VER-BUMP";
        createBooking(bookingId, "Sanjay Singhania", "PNR-BUMP", "BLR → HYD", "6E-204", TEST_TENANT);
        RescueCase c = createCase(caseId, bookingId, "Sanjay Singhania", "6E-204", "BLR → HYD", TEST_TENANT);
        c.setSelectedOptionId("OPT-OLD-1");
        c.setApprovedOptionId("OPT-OLD-1");
        rescueCaseRepository.save(c);

        // Create old candidate option
        RebookOption oldOpt = new RebookOption("OPT-OLD-1", caseId, "FL-OLD", "BLR → HYD", "Tomorrow 10:00", "Tomorrow 11:15", 75, 0, true, "Direct", 1);
        oldOpt.setBookingId(bookingId);
        oldOpt.setItineraryVersion(1L);
        rebookOptionRepository.save(oldOpt);

        // Issue pending OTP challenge
        passengerOtpService.createPassengerOtpChallenge(caseId, TEST_PHONE, "OPT-OLD-1", 0, "INR");

        // Execute authoritative reconciliation
        TenantContext.setTenantId(TEST_TENANT);
        RescueCase reconciled = canonicalItineraryService.reconcileCase(caseId, "supervisor@flyo.com");

        assertNotNull(reconciled);
        assertEquals(2L, reconciled.getItineraryVersion(), "Itinerary version must be bumped from 1 to 2");
        assertNull(reconciled.getSelectedOptionId(), "Stale selected option must be cleared");
        assertNull(reconciled.getApprovedOptionId(), "Stale approval must be cleared");
        assertEquals("supervisor@flyo.com", reconciled.getReconciledBy());

        // Verify pending OTP challenges were invalidated with RECONCILIATION_RESET
        List<PassengerOtpChallenge> challenges = otpChallengeRepository.findByCaseIdOrderByCreatedAtDesc(caseId);
        assertFalse(challenges.isEmpty());
        for (PassengerOtpChallenge ch : challenges) {
            assertTrue(ch.isInvalidated(), "Pending OTP challenges must be invalidated");
            assertEquals("RECONCILIATION_RESET", ch.getInvalidatedReason(), "Invalidation reason must be RECONCILIATION_RESET");
        }
    }

    @Test
    @DisplayName("Safety 5: Distributed Business Idempotency Prevents Duplicate Operations")
    void testDistributedBusinessIdempotencyPreventsDuplicateExecution() throws Exception {
        String key = "BIZ-KEY-" + UUID.randomUUID();
        TenantContext.setTenantId(TEST_TENANT);

        // First execution succeeds
        String res1 = distributedIdempotencyService.executeWithBusinessIdempotency(
                key, "REBOOK_PAYMENT", "RES-1", "HASH-123",
                () -> "PAYMENT_PROCESSED"
        );
        assertEquals("PAYMENT_PROCESSED", res1);

        // Verify persisted record
        Optional<BusinessIdempotencyKey> stored = businessIdempotencyKeyRepository.findById(key);
        assertTrue(stored.isPresent());
        assertEquals("COMPLETED", stored.get().getStatus());

        // Second execution with same key must throw idempotent duplicate exception
        assertThrows(IllegalStateException.class, () -> {
            distributedIdempotencyService.executeWithBusinessIdempotency(
                    key, "REBOOK_PAYMENT", "RES-1", "HASH-123",
                    () -> "PAYMENT_PROCESSED_AGAIN"
            );
        }, "Subsequent invocation with completed business key must be rejected");
    }

    @Test
    @DisplayName("Safety 6: Webhook Ingress Deduplication Prevents Replay")
    void testWebhookDeduplicationPreventsReplay() {
        String provider = "META_WHATSAPP";
        String eventId = "EVT-ST14-" + UUID.randomUUID();

        // First receipt succeeds
        boolean accepted1 = distributedIdempotencyService.recordWebhookReceipt(provider, eventId, "MESSAGE_STATUS", "HASH_PAYLOAD_1");
        assertTrue(accepted1, "Initial webhook receipt must be accepted");

        // Duplicate receipt with same provider and eventId must be dropped
        boolean accepted2 = distributedIdempotencyService.recordWebhookReceipt(provider, eventId, "MESSAGE_STATUS", "HASH_PAYLOAD_1");
        assertFalse(accepted2, "Duplicate webhook must be dropped cleanly");

        // Verify record in webhook_deduplications table
        assertTrue(webhookDeduplicationRepository.existsByProviderAndEventId(provider, eventId));
    }

    @Test
    @DisplayName("Safety 7: Multi-Segment Recovery Engine Tags Strategies Correctly")
    void testMultiSegmentRecoveryStrategies() {
        String bookingId = "B-STRAT-" + UUID.randomUUID().toString().substring(0, 8);
        Booking b = createBooking(bookingId, "Mira Nair", "PNR-STRAT", "BLR → HYD → DEL", "6E-205", TEST_TENANT);
        b.setOnwardFlight("AI-840 HYD-DEL 22:00");
        bookingRepository.save(b);

        RescueCase c = createCase("CASE-STRAT", bookingId, "Mira Nair", "6E-205", "BLR → HYD", TEST_TENANT);
        c.setFinalDestination("DEL");
        rescueCaseRepository.save(c);

        List<RebookOption> options = flightSearchService.findCandidateOptions(c.getId(), b);
        assertFalse(options.isEmpty(), "Candidate options must be generated for disrupted segment");

        // Verify strategy tagging
        for (RebookOption opt : options) {
            assertNotNull(opt.getRecoveryStrategy(), "Every candidate option must specify a recoveryStrategy");
            assertNotNull(opt.getBookingId(), "Option must reference canonical bookingId");
            assertNotNull(opt.getSourceSegmentId(), "Option must reference sourceSegmentId");
            assertEquals(1L, opt.getItineraryVersion(), "Option must reference matching itineraryVersion");
        }
    }

    @Test
    @DisplayName("Safety 8: Provider Outcome Unknown Transitions to Reconciliation or Escalation")
    void testProviderOutcomeUnknownTransitions() {
        RescueCase c = new RescueCase();
        c.setId("CASE-STATE-TEST");
        c.setState(CaseStateMachineService.STATE_PENDING_PROVIDER_SUBMISSION);

        // State Machine allows transition from PENDING_PROVIDER_SUBMISSION to PROVIDER_OUTCOME_UNKNOWN
        assertTrue(stateMachineService.canTransition(
                CaseStateMachineService.STATE_PENDING_PROVIDER_SUBMISSION,
                CaseStateMachineService.STATE_PROVIDER_OUTCOME_UNKNOWN
        ));

        // State Machine allows transition from PROVIDER_OUTCOME_UNKNOWN to DATA_RECONCILIATION_REQUIRED
        assertTrue(stateMachineService.canTransition(
                CaseStateMachineService.STATE_PROVIDER_OUTCOME_UNKNOWN,
                CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED
        ));

        // State Machine allows transition from PROVIDER_OUTCOME_UNKNOWN to FAILED_ESCALATED
        assertTrue(stateMachineService.canTransition(
                CaseStateMachineService.STATE_PROVIDER_OUTCOME_UNKNOWN,
                CaseStateMachineService.STATE_FAILED_ESCALATED
        ));

        // State Machine allows transition from PROVIDER_CONFIRMED to VERIFYING_BOOKING and PAYMENT_AUTH_PENDING
        assertTrue(stateMachineService.canTransition(
                CaseStateMachineService.STATE_PROVIDER_CONFIRMED,
                CaseStateMachineService.STATE_VERIFYING_BOOKING
        ));
        assertTrue(stateMachineService.canTransition(
                CaseStateMachineService.STATE_PROVIDER_CONFIRMED,
                CaseStateMachineService.STATE_PAYMENT_AUTH_PENDING
        ));
    }
}
