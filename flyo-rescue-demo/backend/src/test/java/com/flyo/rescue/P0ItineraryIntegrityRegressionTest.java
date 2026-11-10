package com.flyo.rescue;

import com.flyo.rescue.dto.FlightStatusAlert;
import com.flyo.rescue.model.*;
import com.flyo.rescue.repository.*;
import com.flyo.rescue.security.TenantContext;
import com.flyo.rescue.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 ITINERARY INTEGRITY REGRESSION TEST SUITE.
 *
 * Verifies that every incident, itinerary, flight segment, impact calculation,
 * recovery option, approval, and execution action uses the same verified canonical
 * booking identity and segment, preventing route drift and contamination across all 15 mandatory scenarios.
 */
@SpringBootTest
@ActiveProfiles("test")
public class P0ItineraryIntegrityRegressionTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private FlightAlertService flightAlertService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private CanonicalItineraryService canonicalItineraryService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private FlightSegmentRepository flightSegmentRepository;

    @Autowired
    private RebookOptionRepository rebookOptionRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        TenantContext.setTenantId(TenantContext.DEFAULT_TENANT_ID);
    }

    private RescueCase createBaseCase(String id, String bookingId, String pnr, String origin, String dest, String finalDest, String state) {
        RescueCase c = new RescueCase();
        c.setId(id);
        c.setBookingId(bookingId);
        c.setPnr(pnr);
        c.setPassengerName("Test Passenger");
        c.setPassengerPhone("+919876543210");
        c.setOriginAirport(origin);
        c.setDestinationAirport(dest);
        c.setFinalDestination(finalDest);
        c.setCancelledSegment(origin + " → " + dest);
        c.setState(state);
        c.setItineraryVersion(1L);
        c.setItineraryId("ITIN-" + id);
        c.setDisruptedSegmentId("SEG-" + id);
        return c;
    }

    // =========================================================================
    // SCENARIO 1: BLR → HYD booking with BLR → HYD cancellation.
    // Proves standalone booking maintains destination HYD, options preserve HYD,
    // and impact calculation does not fabricate connections to DEL.
    // =========================================================================
    @Test
    @DisplayName("Scenario 1: BLR -> HYD booking with BLR -> HYD cancellation preserves destination and calculates standalone impact")
    @Transactional
    public void testScenario1_BlrToHydStandaloneDisruption() {
        Booking b = new Booking();
        b.setId("B-TEST-BLR-HYD");
        b.setFlight("6E-206");
        b.setRoute("BLR → HYD");
        b.setDateTime("Tonight 22:00");
        b.setStatus("Monitored");
        b.setPassengerName("Rohan Verma");
        b.setPnr("BLRHYD1");
        b.setPassengerPhone("+919800000001");
        b.setOnwardFlight("None");
        b.setHotelNote("None");
        b.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b);

        canonicalItineraryService.normalizeAndPersistSegments(b);

        FlightStatusAlert alert = new FlightStatusAlert(
                "6E-206",
                "CANCELLED",
                0,
                "Engine inspection",
                "Radar",
                "BLR",
                "HYD"
        );
        alert.setBookingId(b.getId());

        List<RescueCase> cases = flightAlertService.handleFlightStatusAlert(alert);
        assertFalse(cases.isEmpty(), "Case must be created for valid disruption alert");
        RescueCase c = cases.get(0);

        assertEquals("BLR", c.getOriginAirport());
        assertEquals("HYD", c.getDestinationAirport());
        assertEquals("HYD", c.getFinalDestination());
        assertEquals("BLRHYD1", c.getPnr());
        assertEquals(0, c.getOnwardSegmentsCount());
        assertTrue(c.getImpact().contains("Standalone trip disrupted") || c.getImpact().contains("Direct flight cancelled"),
                "Impact should describe standalone flight without onward connection: " + c.getImpact());
        assertEquals("None", c.getMissedConnection());

        List<RebookOption> options = caseService.getOptionsForCase(c.getId());
        assertFalse(options.isEmpty(), "Options must be generated");
        for (RebookOption opt : options) {
            assertEquals("HYD", opt.getFinalDestination(), "Every recovery option must preserve verified final destination HYD");
            assertTrue(opt.getRoute().contains("HYD"), "Route must contain HYD: " + opt.getRoute());
            assertFalse(opt.getRoute().contains("DEL"), "Standalone BLR -> HYD must NEVER route to DEL: " + opt.getRoute());
            assertEquals(b.getId(), opt.getBookingId());
            assertEquals(c.getId(), opt.getIncidentId());
        }
    }

    // =========================================================================
    // SCENARIO 2: Multi-segment BLR → HYD → DEL itinerary with BLR → HYD cancellation.
    // Proves disrupted segment is BLR -> HYD, onward segment is HYD -> DEL,
    // final destination is DEL, and recovery options preserve final destination DEL.
    // =========================================================================
    @Test
    @DisplayName("Scenario 2: Multi-segment BLR -> HYD -> DEL itinerary with BLR -> HYD cancellation preserves final destination DEL")
    @Transactional
    public void testScenario2_MultiSegmentBlrHydDelDisruption() {
        Booking b = new Booking();
        b.setId("B-TEST-MULTI-BLR-DEL");
        b.setFlight("6E-206");
        b.setRoute("BLR → HYD");
        b.setDateTime("Tonight 18:00");
        b.setStatus("Monitored");
        b.setPassengerName("Priya Sen");
        b.setPnr("BLRDEL2");
        b.setPassengerPhone("+919800000002");
        b.setOnwardFlight("AI-839 HYD → DEL Tonight 22:30");
        b.setHotelNote("Delhi airport transit hotel");
        b.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b);

        canonicalItineraryService.normalizeAndPersistSegments(b);

        FlightStatusAlert alert = new FlightStatusAlert(
                "6E-206",
                "CANCELLED",
                0,
                "Weather delay",
                "Radar",
                "BLR",
                "HYD"
        );
        alert.setBookingId(b.getId());

        List<RescueCase> cases = flightAlertService.handleFlightStatusAlert(alert);
        assertFalse(cases.isEmpty());
        RescueCase c = cases.get(0);

        assertEquals("BLR", c.getOriginAirport());
        assertEquals("HYD", c.getDestinationAirport());
        assertEquals("DEL", c.getFinalDestination(), "Final destination must be verified as DEL");
        assertEquals(1, c.getOnwardSegmentsCount(), "Must identify 1 onward segment");
        assertTrue(c.getImpact().contains("DEL"), "Impact calculation must account for onward connection to DEL");

        List<RebookOption> options = caseService.getOptionsForCase(c.getId());
        assertFalse(options.isEmpty());
        for (RebookOption opt : options) {
            assertEquals("DEL", opt.getFinalDestination(), "Every recovery option must preserve final destination DEL");
            assertTrue(opt.getRoute().contains("HYD") || opt.getRoute().contains("DEL"),
                    "Option route must connect at HYD transfer hub or DEL final destination: " + opt.getRoute());
        }
    }

    // =========================================================================
    // SCENARIO 3: HYD → DEL cancellation with a valid HYD → DEL booking.
    // Proves that when booking is HYD -> DEL, options preserve DEL.
    // =========================================================================
    @Test
    @DisplayName("Scenario 3: HYD -> DEL cancellation with valid HYD -> DEL booking preserves DEL")
    @Transactional
    public void testScenario3_HydDelValidBooking() {
        Booking b = new Booking();
        b.setId("B-TEST-HYD-DEL");
        b.setFlight("AI-839");
        b.setRoute("HYD → DEL");
        b.setDateTime("Tonight 21:00");
        b.setStatus("Monitored");
        b.setPassengerName("Ananya Sharma");
        b.setPnr("HYD123");
        b.setPassengerPhone("+919876543210");
        b.setOnwardFlight("AI-187 DEL → Toronto (YYZ) Tomorrow 09:30");
        b.setHotelNote("Delhi hotel non-refundable");
        b.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b);

        canonicalItineraryService.normalizeAndPersistSegments(b);

        FlightStatusAlert alert = new FlightStatusAlert(
                "AI-839",
                "CANCELLED",
                0,
                "Operational cancellation",
                "Radar",
                "HYD",
                "DEL"
        );
        alert.setBookingId(b.getId());

        List<RescueCase> cases = flightAlertService.handleFlightStatusAlert(alert);
        assertFalse(cases.isEmpty());
        RescueCase c = cases.get(0);

        assertEquals("HYD", c.getOriginAirport());
        assertEquals("DEL", c.getDestinationAirport());
        assertEquals("YYZ", c.getFinalDestination());

        List<RebookOption> options = caseService.getOptionsForCase(c.getId());
        assertFalse(options.isEmpty());
        for (RebookOption opt : options) {
            assertTrue(opt.getRoute().startsWith("HYD"), "Route must originate at HYD: " + opt.getRoute());
            assertTrue(opt.getRoute().contains("DEL"), "Route must connect or terminate at DEL: " + opt.getRoute());
        }
    }

    // =========================================================================
    // SCENARIO 4: Same passenger with multiple bookings.
    // Proves disruption on one booking cannot contaminate another booking.
    // =========================================================================
    @Test
    @DisplayName("Scenario 4: Same passenger with multiple bookings keeps bookings isolated")
    @Transactional
    public void testScenario4_SamePassengerMultipleBookings() {
        String passengerPhone = "+919998887771";

        Booking b1 = new Booking();
        b1.setId("B-PAX-BOOKING-1");
        b1.setFlight("6E-101");
        b1.setRoute("BLR → HYD");
        b1.setDateTime("Tomorrow 10:00");
        b1.setStatus("Monitored");
        b1.setPassengerName("Vikram Seth");
        b1.setPnr("PNR111");
        b1.setPassengerPhone(passengerPhone);
        b1.setOnwardFlight("None");
        b1.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b1);
        canonicalItineraryService.normalizeAndPersistSegments(b1);

        Booking b2 = new Booking();
        b2.setId("B-PAX-BOOKING-2");
        b2.setFlight("UK-802");
        b2.setRoute("DEL → BOM");
        b2.setDateTime("In 3 days 15:00");
        b2.setStatus("Monitored");
        b2.setPassengerName("Vikram Seth");
        b2.setPnr("PNR222");
        b2.setPassengerPhone(passengerPhone);
        b2.setOnwardFlight("None");
        b2.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b2);
        canonicalItineraryService.normalizeAndPersistSegments(b1);
        canonicalItineraryService.normalizeAndPersistSegments(b2);
        canonicalItineraryService.normalizeAndPersistSegments(b2);

        FlightStatusAlert alert = new FlightStatusAlert(
                "6E-101",
                "CANCELLED",
                0,
                "Technical delay",
                "Radar",
                "BLR",
                "HYD"
        );
        alert.setBookingId(b1.getId());

        List<RescueCase> cases = flightAlertService.handleFlightStatusAlert(alert);
        assertFalse(cases.isEmpty());
        RescueCase c1 = cases.get(0);

        assertEquals("B-PAX-BOOKING-1", c1.getBookingId());
        assertEquals("PNR111", c1.getPnr());
        assertEquals("HYD", c1.getFinalDestination());

        Booking freshB2 = bookingRepository.findById("B-PAX-BOOKING-2").orElseThrow();
        assertEquals("Monitored", freshB2.getStatus());
        assertEquals("DEL → BOM", freshB2.getRoute());
    }

    // =========================================================================
    // SCENARIO 5: Same PNR with multiple flight segments.
    // Proves distinct segment IDs and indices are maintained without collision.
    // =========================================================================
    @Test
    @DisplayName("Scenario 5: Same PNR with multiple flight segments maintains unique segment identity")
    @Transactional
    public void testScenario5_SamePnrMultipleSegments() {
        Booking b = new Booking();
        b.setId("B-MULTI-SEG-PNR");
        b.setFlight("6E-301");
        b.setRoute("BLR → HYD");
        b.setDateTime("Today 14:00");
        b.setStatus("Monitored");
        b.setPassengerName("Sunita Rao");
        b.setPnr("MULTISEG1");
        b.setPassengerPhone("+919811122233");
        b.setOnwardFlight("AI-502 HYD → DEL Today 18:00");
        b.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b);

        List<FlightSegment> segments = canonicalItineraryService.normalizeAndPersistSegments(b);
        assertEquals(2, segments.size(), "Must generate exactly 2 distinct segments");

        FlightSegment seg0 = segments.get(0);
        FlightSegment seg1 = segments.get(1);

        assertEquals(1, seg0.getSegmentIndex());
        assertEquals("6E-301", seg0.getFlightNumber());
        assertEquals("BLR", seg0.getOriginAirport());
        assertEquals("HYD", seg0.getDestinationAirport());

        assertEquals(2, seg1.getSegmentIndex());
        assertEquals("AI-502", seg1.getFlightNumber());
        assertEquals("HYD", seg1.getOriginAirport());
        assertEquals("DEL", seg1.getDestinationAirport());

        assertNotEquals(seg0.getId(), seg1.getId(), "Segment IDs must be distinct");
        assertEquals(seg0.getItineraryId(), seg1.getItineraryId(), "Both segments belong to same itinerary");
    }

    // =========================================================================
    // SCENARIO 6: Flight number matches but airport route does not.
    // Disruption alert route does not match booking segment. Must flag mismatch
    // and fail-closed to DATA_RECONCILIATION_REQUIRED.
    // =========================================================================
    @Test
    @DisplayName("Scenario 6: Flight number matches but route does not -> DATA_RECONCILIATION_REQUIRED")
    @Transactional
    public void testScenario6_FlightNumberMatchesButRouteMismatched() {
        Booking b = new Booking();
        b.setId("B-ROUTE-MISMATCH");
        b.setFlight("6E-206");
        b.setRoute("BLR → HYD");
        b.setDateTime("Tonight 22:00");
        b.setStatus("Monitored");
        b.setPassengerName("Mismatched Pax");
        b.setPnr("MISMATCH1");
        b.setPassengerPhone("+919700000001");
        b.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b);
        canonicalItineraryService.normalizeAndPersistSegments(b);

        FlightStatusAlert alert = new FlightStatusAlert(
                "6E-206",
                "CANCELLED",
                0,
                "Weather",
                "Radar",
                "BOM",
                "DEL"
        );
        alert.setBookingId(b.getId());

        List<RescueCase> cases = flightAlertService.handleFlightStatusAlert(alert);
        assertFalse(cases.isEmpty(), "Case should be captured");
        RescueCase c = cases.get(0);

        assertEquals(CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED, c.getState(),
                "Incident must transition to DATA_RECONCILIATION_REQUIRED on route mismatch");
        assertNotNull(c.getMismatchReason());
        assertTrue(c.getMismatchReason().contains("sector mismatch") || c.getMismatchReason().contains("does not match"),
                "Mismatch reason must specify sector mismatch: " + c.getMismatchReason());
    }

    // =========================================================================
    // SCENARIO 7: Correct route but incorrect booking ID.
    // Validating an option or segment referencing an invalid booking ID fails.
    // =========================================================================
    @Test
    @DisplayName("Scenario 7: Correct route but incorrect booking ID fails referential validation")
    public void testScenario7_IncorrectBookingId() {
        FlightSegment segment = new FlightSegment();
        segment.setId("SEG-CORRECT-ROUTE");
        segment.setBookingId("B-NON-EXISTENT");
        segment.setOriginAirport("BLR");
        segment.setDestinationAirport("HYD");

        RescueCase rc = createBaseCase("C-TEST-SCENARIO-7", "B-ANOTHER-BOOKING", "PNR-DIFF", "BLR", "HYD", "HYD", CaseStateMachineService.STATE_OPTIONS_READY);
        Booking b = new Booking();
        b.setId("B-ANOTHER-BOOKING");
        b.setAgency("Flyo Corporate Travel");

        CanonicalItineraryService.ItineraryValidationResult result =
                canonicalItineraryService.validateItineraryIntegrity(rc, b, segment);

        assertFalse(result.isValid(), "Referential validation must fail when booking IDs do not match");
        assertTrue(result.mismatchReason().contains("belongs to booking"));
    }

    // =========================================================================
    // SCENARIO 8: Stale cached recovery options.
    // Stale options with mismatched cache keys or destinations are evicted and regenerated.
    // =========================================================================
    @Test
    @DisplayName("Scenario 8: Stale cached recovery options are evicted and regenerated")
    @Transactional
    public void testScenario8_StaleCachedOptionsEvicted() {
        Booking b = new Booking();
        b.setId("B-STALE-OPTIONS");
        b.setFlight("6E-206");
        b.setRoute("BLR → HYD");
        b.setDateTime("Tonight 22:00");
        b.setStatus("Cancelled");
        b.setPassengerName("Kavita Nair");
        b.setPnr("STALE1");
        b.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b);
        canonicalItineraryService.normalizeAndPersistSegments(b);

        RescueCase c = createBaseCase("C-STALE-TEST", b.getId(), b.getPnr(), "BLR", "HYD", "HYD", CaseStateMachineService.STATE_OPTIONS_READY);
        c.setPassengerName(b.getPassengerName());
        c.setCancelledSegment("BLR → HYD (6E-206)");
        rescueCaseRepository.save(c);

        RebookOption staleOption = new RebookOption();
        staleOption.setId("OPT-STALE-WRONG-DEST");
        staleOption.setCaseId(c.getId());
        staleOption.setBookingId(b.getId());
        staleOption.setFlight("AI-STALE");
        staleOption.setRoute("HYD → DEL");
        staleOption.setFinalDestination("DEL");
        staleOption.setDeparture("21:00");
        staleOption.setArrival("23:00");
        staleOption.setDeterministicCacheKey("OLD_KEY_V0");
        rebookOptionRepository.save(staleOption);

        List<RebookOption> freshOptions = caseService.getOptionsForCase(c.getId());
        assertFalse(freshOptions.isEmpty());

        for (RebookOption opt : freshOptions) {
            assertEquals("HYD", opt.getFinalDestination(), "Regenerated options must strictly preserve destination HYD");
            assertNotEquals("OPT-STALE-WRONG-DEST", opt.getId(), "Stale option must have been evicted");
        }
    }

    // =========================================================================
    // SCENARIO 9: Itinerary changes after option generation.
    // Approving option with outdated itinerary version fails server-side validation.
    // =========================================================================
    @Test
    @DisplayName("Scenario 9: Itinerary version change after option generation rejects stale approval")
    @Transactional
    public void testScenario9_ItineraryVersionChangeRejectsApproval() {
        Booking b = new Booking();
        b.setId("B-ITIN-CHANGE");
        b.setFlight("6E-206");
        b.setRoute("BLR → HYD");
        b.setDateTime("Tonight 22:00");
        b.setStatus("Cancelled");
        b.setPassengerName("Deepak Joshi");
        b.setPnr("ITINVER1");
        b.setItineraryVersion(1L);
        b.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b);
        canonicalItineraryService.normalizeAndPersistSegments(b);

        RescueCase c = createBaseCase("C-ITIN-CHANGE", b.getId(), b.getPnr(), "BLR", "HYD", "HYD", CaseStateMachineService.STATE_OPTIONS_READY);
        c.setPassengerName(b.getPassengerName());
        c.setCancelledSegment("BLR → HYD (6E-206)");
        rescueCaseRepository.save(c);

        List<RebookOption> options = caseService.generateAndRankOptions(c.getId());
        RebookOption opt = options.get(0);

        // Simulate itinerary version change to version 2
        b.setItineraryVersion(2L);
        bookingRepository.save(b);

        assertThrows(RuntimeException.class, () -> {
            caseService.approveOption(c.getId(), opt.getId(), "Operator");
        }, "Approving option with mismatched itinerary version must be rejected");
    }

    // =========================================================================
    // SCENARIO 10: Attempted approval with mismatched segment.
    // Attempting to approve an option from a different case or segment is rejected.
    // =========================================================================
    @Test
    @DisplayName("Scenario 10: Attempted approval with mismatched segment or case rejected")
    @Transactional
    public void testScenario10_MismatchedSegmentApprovalRejected() {
        Booking b1 = new Booking();
        b1.setId("B-1");
        b1.setFlight("6E-101");
        b1.setRoute("BLR → HYD");
        b1.setDateTime("Tonight 22:00");
        b1.setStatus("Cancelled");
        b1.setPassengerName("Pax One");
        b1.setPnr("PNR1");
        b1.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b1);

        Booking b2 = new Booking();
        b2.setId("B-2");
        b2.setFlight("UK-999");
        b2.setRoute("DEL → BOM");
        b2.setDateTime("Tonight 23:00");
        b2.setStatus("Cancelled");
        b2.setPassengerName("Pax Two");
        b2.setPnr("PNR2");
        b2.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b2);
        canonicalItineraryService.normalizeAndPersistSegments(b1);
        canonicalItineraryService.normalizeAndPersistSegments(b2);

        RescueCase c1 = createBaseCase("C-CASE-1", "B-1", "PNR1", "BLR", "HYD", "HYD", CaseStateMachineService.STATE_OPTIONS_READY);
        rescueCaseRepository.save(c1);

        RescueCase c2 = createBaseCase("C-CASE-2", "B-2", "PNR2", "DEL", "BOM", "BOM", CaseStateMachineService.STATE_OPTIONS_READY);
        rescueCaseRepository.save(c2);

        RebookOption opt2 = new RebookOption();
        opt2.setId("OPT-FOR-CASE-2");
        opt2.setCaseId(c2.getId());
        opt2.setBookingId("B-2");
        opt2.setFlight("UK-999");
        opt2.setRoute("DEL → BOM");
        opt2.setFinalDestination("BOM");
        opt2.setDeparture("21:00");
        opt2.setArrival("23:00");
        rebookOptionRepository.save(opt2);

        assertThrows(RuntimeException.class, () -> {
            caseService.approveOption(c1.getId(), opt2.getId(), "Operator");
        }, "Cannot approve an option that belongs to a different case");
    }

    // =========================================================================
    // SCENARIO 11: Attempted rebooking with mismatched itinerary.
    // Server-side rebooking execution blocks on mismatched or reconciliation state.
    // =========================================================================
    @Test
    @DisplayName("Scenario 11: Attempted rebooking with mismatched itinerary fails closed")
    @Transactional
    public void testScenario11_MismatchedItineraryRebookingBlocked() {
        RescueCase c = createBaseCase("C-REBOOK-MISMATCH", "B-REBOOK-MISMATCH", "MISMATCH_REBOOK", "BLR", "HYD", "HYD", CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED);
        c.setMismatchReason("Route mismatch: BLR -> HYD vs HYD -> DEL");
        rescueCaseRepository.save(c);

        assertThrows(RuntimeException.class, () -> {
            caseService.rebookCase(c.getId());
        }, "Rebooking must fail closed when incident is in DATA_RECONCILIATION_REQUIRED");
    }

    // =========================================================================
    // SCENARIO 12: Duplicate disruption events.
    // Handled idempotently without corrupting canonical segments.
    // =========================================================================
    @Test
    @DisplayName("Scenario 12: Duplicate disruption events handled idempotently")
    @Transactional
    public void testScenario12_DuplicateDisruptionEventsIdempotent() {
        Booking b = new Booking();
        b.setId("B-DUP-ALERT");
        b.setFlight("6E-555");
        b.setRoute("BLR → HYD");
        b.setDateTime("Tonight 23:00");
        b.setStatus("Monitored");
        b.setPassengerName("Anil Kapoor");
        b.setPnr("DUP123");
        b.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b);
        canonicalItineraryService.normalizeAndPersistSegments(b);

        FlightStatusAlert alert = new FlightStatusAlert(
                "6E-555",
                "CANCELLED",
                0,
                "Weather",
                "Radar",
                "BLR",
                "HYD"
        );
        alert.setBookingId(b.getId());

        List<RescueCase> cases1 = flightAlertService.handleFlightStatusAlert(alert);
        assertFalse(cases1.isEmpty());

        List<RescueCase> cases2 = flightAlertService.handleFlightStatusAlert(alert);
        assertFalse(cases2.isEmpty());

        assertEquals(cases1.get(0).getId(), cases2.get(0).getId(), "Duplicate alert must return existing case ID");
        assertEquals("HYD", cases2.get(0).getFinalDestination());
    }

    // =========================================================================
    // SCENARIO 13: Out-of-order disruption events.
    // Disruption events arriving after terminal states do not corrupt state.
    // =========================================================================
    @Test
    @DisplayName("Scenario 13: Out-of-order disruption events preserve terminal state")
    @Transactional
    public void testScenario13_OutOfOrderDisruptionPreservesTerminalState() {
        Booking b = new Booking();
        b.setId("B-TERMINAL-STATE");
        b.setFlight("6E-777");
        b.setRoute("BLR → HYD");
        b.setDateTime("Tonight 21:00");
        b.setStatus("Rescued");
        b.setPassengerName("Terminal Pax");
        b.setPnr("TERM123");
        b.setPassengerName("Terminal Passenger");
        b.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b);
        canonicalItineraryService.normalizeAndPersistSegments(b);

        RescueCase c = createBaseCase("C-TERMINAL-CASE", b.getId(), b.getPnr(), "BLR", "HYD", "HYD", "DONE");
        rescueCaseRepository.save(c);

        FlightStatusAlert lateAlert = new FlightStatusAlert(
                "6E-777",
                "CANCELLED",
                0,
                "Late alert",
                "Radar",
                "BLR",
                "HYD"
        );
        lateAlert.setBookingId(b.getId());

        List<RescueCase> processed = flightAlertService.handleFlightStatusAlert(lateAlert);
        assertFalse(processed.isEmpty());
        assertEquals("DONE", processed.get(0).getState(), "Terminal state DONE must be preserved against late alerts");
    }

    // =========================================================================
    // SCENARIO 14: Missing booking or segment identity.
    // Disruption without resolvable booking fails safely without corrupting data.
    // =========================================================================
    @Test
    @DisplayName("Scenario 14: Missing booking or segment identity handled safely")
    public void testScenario14_MissingBookingHandledSafely() {
        FlightStatusAlert alert = new FlightStatusAlert(
                "UNKNOWN-999",
                "CANCELLED",
                0,
                "Ghost flight",
                "Radar",
                "XYZ",
                "ABC"
        );
        alert.setBookingId("NON-EXISTENT-BOOKING");

        List<RescueCase> result = flightAlertService.handleFlightStatusAlert(alert);
        assertTrue(result.isEmpty(), "Non-existent booking must not create a fabricated case");
    }

    // =========================================================================
    // SCENARIO 15: Provider response references a different flight.
    // Verification rejects execution if option destination does not match case final destination.
    // =========================================================================
    @Test
    @DisplayName("Scenario 15: Provider response execution verification rejects destination corruption")
    @Transactional
    public void testScenario15_ProviderResponseMismatchedFlightRejected() {
        Booking b = new Booking();
        b.setId("B-PROV");
        b.setFlight("6E-206");
        b.setRoute("BLR → HYD");
        b.setDateTime("Tonight 22:00");
        b.setStatus("Cancelled");
        b.setPassengerName("Prov Passenger");
        b.setPnr("PROV1");
        b.setAgency("Flyo Corporate Travel");
        bookingRepository.save(b);
        canonicalItineraryService.normalizeAndPersistSegments(b);

        RescueCase c = createBaseCase("C-PROVIDER-MISMATCH", "B-PROV", "PROV1", "BLR", "HYD", "HYD", CaseStateMachineService.STATE_APPROVED);
        c.setApprovedOptionId("OPT-PROV-1");
        rescueCaseRepository.save(c);

        RebookOption opt = new RebookOption();
        opt.setId("OPT-PROV-1");
        opt.setCaseId(c.getId());
        opt.setBookingId("B-PROV");
        opt.setFlight("6E-NEW");
        opt.setRoute("HYD → DEL"); // Does not preserve case final destination HYD!
        opt.setFinalDestination("DEL");
        opt.setDeparture("21:00");
        opt.setArrival("23:00");
        rebookOptionRepository.save(opt);

        assertThrows(RuntimeException.class, () -> {
            caseService.rebookCase(c.getId());
        }, "Rebooking must reject option whose destination does not preserve case final destination");
    }
}
