package com.flyo.rescue.service;

import com.flyo.rescue.dto.FlightStatusAlert;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.FlightSegment;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.FlightSegmentRepository;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authoritative Canonical Itinerary Identity, Segment Normalization, and Verification Service.
 *
 * Implements Phases 2, 3, 4, 5, 6 & 9 of P0 Itinerary Integrity:
 * - Enforces immutable segment identities and referential integrity.
 * - Prevents route drift (e.g. BLR -> HYD silently becoming HYD -> DEL).
 * - Distinguishes between single-segment trips, disrupted segments, and verified onward connections.
 * - Enforces fail-closed state transitions to DATA_RECONCILIATION_REQUIRED on mismatch.
 */
@Service
public class CanonicalItineraryService {

    private static final Logger log = LoggerFactory.getLogger(CanonicalItineraryService.class);

    private final BookingRepository bookingRepository;
    private final FlightSegmentRepository segmentRepository;
    private final RescueCaseRepository caseRepository;
    private final RebookOptionRepository optionRepository;
    private final CaseStateMachineService stateMachineService;
    private final AuditService auditService;

    public CanonicalItineraryService(BookingRepository bookingRepository,
                                     FlightSegmentRepository segmentRepository,
                                     RescueCaseRepository caseRepository,
                                     RebookOptionRepository optionRepository,
                                     CaseStateMachineService stateMachineService,
                                     AuditService auditService) {
        this.bookingRepository = bookingRepository;
        this.segmentRepository = segmentRepository;
        this.caseRepository = caseRepository;
        this.optionRepository = optionRepository;
        this.stateMachineService = stateMachineService;
        this.auditService = auditService;
    }

    public static String normalizeFlight(String fl) {
        if (fl == null) return "";
        return fl.trim().toUpperCase().replaceAll("[\\s-_]+", "");
    }

    public static String formatFlight(String fl) {
        if (fl == null || fl.isBlank()) return "UNKNOWN";
        String clean = fl.trim().toUpperCase().replaceAll("[\\s_]+", "-");
        if (clean.matches("^[A-Z0-9]{2}[0-9]{3,4}$")) {
            return clean.substring(0, 2) + "-" + clean.substring(2);
        }
        return clean;
    }

    /**
     * Normalizes a booking into canonical FlightSegments and saves them.
     */
    @Transactional
    public List<FlightSegment> normalizeAndPersistSegments(Booking booking) {
        if (booking == null) return List.of();

        if (booking.getPassengerId() == null || booking.getPassengerId().isBlank()) {
            booking.setPassengerId("PAX-" + booking.getPnr());
        }
        if (booking.getItineraryId() == null || booking.getItineraryId().isBlank()) {
            booking.setItineraryId("ITIN-" + booking.getPnr());
        }
        if (booking.getItineraryVersion() == null) {
            booking.setItineraryVersion(1L);
        }

        List<FlightSegment> existing = segmentRepository.findByBookingIdOrderBySegmentIndexAsc(booking.getId());
        if (!existing.isEmpty() && existing.get(0).getItineraryVersion().equals(booking.getItineraryVersion())) {
            return existing;
        }

        segmentRepository.deleteByBookingId(booking.getId());

        List<FlightSegment> segments = new ArrayList<>();

        // Segment 1: Primary leg
        String seg1Id = "SEG-" + booking.getId() + "-1";
        String origin1 = booking.getOriginAirport();
        String dest1 = booking.getDestinationAirport();
        String flight1 = formatFlight(booking.getFlight());
        String schedDep1 = booking.getDateTime() != null ? booking.getDateTime() : "Scheduled";

        FlightSegment seg1 = new FlightSegment(
                seg1Id, booking.getId(), booking.getPassengerId(), booking.getPnr(),
                booking.getItineraryId(), 1, flight1, origin1, dest1, schedDep1, "Scheduled"
        );
        seg1.setItineraryVersion(booking.getItineraryVersion());
        seg1.setTenantId(booking.getTenantId());
        segments.add(seg1);

        // Segment 2+: Onward flights if present
        if (booking.isMultiSegment()) {
            String onward = booking.getOnwardFlight();
            String flight2 = "ONWARD";
            String origin2 = dest1;
            String dest2 = booking.getFinalDestination();
            String schedDep2 = "Connecting";

            // Extract onward flight number if present
            Pattern fPattern = Pattern.compile("(?i)\\b([A-Z0-9]{2}[-\\s_]?[0-9]{3,4})\\b");
            Matcher fm = fPattern.matcher(onward);
            if (fm.find()) {
                flight2 = formatFlight(fm.group(1));
            }

            // Extract onward route if present
            Pattern rPattern = Pattern.compile("\\b([A-Z]{3})\\s*(?:→|->|to|-)\\s*([A-Za-z\\s()]+)");
            Matcher rm = rPattern.matcher(onward);
            if (rm.find()) {
                origin2 = rm.group(1).trim().toUpperCase();
            }

            String seg2Id = "SEG-" + booking.getId() + "-2";
            FlightSegment seg2 = new FlightSegment(
                    seg2Id, booking.getId(), booking.getPassengerId(), booking.getPnr(),
                    booking.getItineraryId(), 2, flight2, origin2, dest2, schedDep2, "Scheduled"
            );
            seg2.setItineraryVersion(booking.getItineraryVersion());
            seg2.setTenantId(booking.getTenantId());
            segments.add(seg2);
        }

        return segmentRepository.saveAll(segments);
    }

    /**
     * Resolves the canonical segment matching a disruption alert or flight cancellation request.
     */
    public Optional<FlightSegment> resolveDisruptedSegment(Booking booking, String alertFlight, String origin, String destination) {
        List<FlightSegment> segments = segmentRepository.findByBookingIdOrderBySegmentIndexAsc(booking.getId());
        if (segments.isEmpty()) {
            segments = normalizeAndPersistSegments(booking);
        }

        String normAlertFlight = normalizeFlight(alertFlight);

        for (FlightSegment seg : segments) {
            boolean flightMatches = normAlertFlight.isBlank() || normalizeFlight(seg.getFlightNumber()).equals(normAlertFlight);
            boolean originMatches = (origin == null || origin.isBlank() || seg.getOriginAirport().equalsIgnoreCase(origin.trim()));
            boolean destMatches = (destination == null || destination.isBlank() || seg.getDestinationAirport().equalsIgnoreCase(destination.trim()));

            if (flightMatches && originMatches && destMatches) {
                return Optional.of(seg);
            }
        }

        // If route matches but flight was omitted, or flight matches primary leg
        if (!segments.isEmpty()) {
            FlightSegment primary = segments.get(0);
            if (normAlertFlight.isBlank() || normalizeFlight(primary.getFlightNumber()).equals(normAlertFlight)) {
                if (origin == null || origin.isBlank() || primary.getOriginAirport().equalsIgnoreCase(origin.trim())) {
                    if (destination == null || destination.isBlank() || primary.getDestinationAirport().equalsIgnoreCase(destination.trim())) {
                        return Optional.of(primary);
                    }
                }
            }
        }

        return Optional.empty();
    }

    public record ItineraryValidationResult(boolean isValid, String mismatchReason, String mismatchField) {
        public static ItineraryValidationResult ok() {
            return new ItineraryValidationResult(true, null, null);
        }
        public static ItineraryValidationResult mismatch(String field, String reason) {
            return new ItineraryValidationResult(false, reason, field);
        }
    }

    /**
     * Phase 3 & Phase 6: Deterministic validation of route, segment, booking and passenger identity.
     */
    public ItineraryValidationResult validateItineraryIntegrity(RescueCase rescueCase, Booking booking, FlightSegment segment) {
        if (rescueCase == null) {
            return ItineraryValidationResult.mismatch("INCIDENT", "Incident case record is null.");
        }
        if (booking == null) {
            return ItineraryValidationResult.mismatch("BOOKING", "Underlying booking record missing for case " + rescueCase.getId());
        }
        if (!booking.getId().equals(rescueCase.getBookingId())) {
            return ItineraryValidationResult.mismatch("BOOKING_ID",
                    String.format("Incident booking_id (%s) does not match passenger booking ID (%s).",
                            rescueCase.getBookingId(), booking.getId()));
        }
        if (segment == null) {
            return ItineraryValidationResult.mismatch("SEGMENT", "Canonical flight segment identity could not be resolved for booking " + booking.getId());
        }
        if (!segment.getBookingId().equals(booking.getId())) {
            return ItineraryValidationResult.mismatch("SEGMENT_BOOKING",
                    String.format("Disrupted segment %s belongs to booking %s, not %s.",
                            segment.getId(), segment.getBookingId(), booking.getId()));
        }

        // Flight number validation
        String caseFlight = normalizeFlight(rescueCase.getFlightNumber() != null ? rescueCase.getFlightNumber() : rescueCase.getFlight());
        String segFlight = normalizeFlight(segment.getFlightNumber());
        if (!caseFlight.isBlank() && !segFlight.isBlank() && !caseFlight.equals(segFlight)) {
            return ItineraryValidationResult.mismatch("FLIGHT_NUMBER",
                    String.format("Disrupted flight number %s does not match canonical segment %s (%s).",
                            caseFlight, segment.getId(), segFlight));
        }

        // Origin and destination validation
        if (rescueCase.getOriginAirport() != null && !rescueCase.getOriginAirport().isBlank()) {
            if (!rescueCase.getOriginAirport().equalsIgnoreCase(segment.getOriginAirport())) {
                return ItineraryValidationResult.mismatch("ORIGIN_AIRPORT",
                        String.format("Disrupted segment origin %s does not match canonical origin %s.",
                                rescueCase.getOriginAirport(), segment.getOriginAirport()));
            }
        }
        if (rescueCase.getDestinationAirport() != null && !rescueCase.getDestinationAirport().isBlank()) {
            if (!rescueCase.getDestinationAirport().equalsIgnoreCase(segment.getDestinationAirport())) {
                return ItineraryValidationResult.mismatch("DESTINATION_AIRPORT",
                        String.format("Disrupted segment destination %s does not match canonical destination %s.",
                                rescueCase.getDestinationAirport(), segment.getDestinationAirport()));
            }
        }

        // Itinerary version check (Optimistic concurrency & state freshness)
        if (rescueCase.getItineraryVersion() != null && !rescueCase.getItineraryVersion().equals(booking.getItineraryVersion())) {
            return ItineraryValidationResult.mismatch("ITINERARY_VERSION",
                    String.format("Incident itinerary version v%d is stale compared to current booking version v%d.",
                            rescueCase.getItineraryVersion(), booking.getItineraryVersion()));
        }

        return ItineraryValidationResult.ok();
    }

    /**
     * Validates that a generated or approved rebooking option preserves the intended journey destination
     * and originates from the passenger's actual disrupted origin.
     */
    public ItineraryValidationResult validateOptionRoute(RescueCase rescueCase, Booking booking, RebookOption option) {
        if (option == null) {
            return ItineraryValidationResult.mismatch("OPTION", "Rebooking option is null.");
        }

        String intendedFinalDest = booking.getFinalDestination();
        String optionRoute = option.getRoute() != null ? option.getRoute() : "";

        // Parse option origin and option destination
        String optOrigin = option.getOriginAirport();
        String optDest = option.getDestinationAirport();

        if (optOrigin == null || optDest == null) {
            String r = optionRoute.replaceAll("\\s*(?:→|->|to)\\s*", "→");
            String[] parts = r.split("→");
            if (parts.length >= 2) {
                if (optOrigin == null) {
                    Matcher m = Pattern.compile("\\b([A-Z]{3})\\b").matcher(parts[0]);
                    optOrigin = m.find() ? m.group(1) : rescueCase.getOriginAirport();
                }
                if (optDest == null) {
                    Matcher m = Pattern.compile("\\b([A-Z]{3})\\b").matcher(parts[parts.length - 1]);
                    optDest = m.find() ? m.group(1) : intendedFinalDest;
                }
            }
        }

        // 1. Passenger stranded origin check: Option MUST originate from the disrupted segment origin
        String canonicalOrigin = rescueCase.getOriginAirport() != null ? rescueCase.getOriginAirport() : booking.getOriginAirport();
        if (optOrigin != null && canonicalOrigin != null && !optOrigin.equalsIgnoreCase(canonicalOrigin)) {
            return ItineraryValidationResult.mismatch("OPTION_ORIGIN_MISMATCH",
                    String.format("Option departs from %s, but passenger is stranded at %s. Disrupted leg cannot be boarded.",
                            optOrigin, canonicalOrigin));
        }

        // 2. Destination preservation check
        // If standalone (single segment), option must end at booking's destination!
        // E.g., BLR -> HYD must never become HYD -> DEL or BLR -> DEL
        if (!booking.isMultiSegment()) {
            String bookingDest = booking.getDestinationAirport();
            if (optDest != null && !optDest.equalsIgnoreCase(bookingDest)) {
                return ItineraryValidationResult.mismatch("DESTINATION_CORRUPTION",
                        String.format("Standalone booking destination is %s. Recovery option targets %s, corrupting passenger destination.",
                                bookingDest, optDest));
            }
        } else {
            // Multi-segment booking: Option must either connect to intermediate transfer hub or final destination
            String intermediateTransfer = booking.getDestinationAirport();
            if (optDest != null && !optDest.equalsIgnoreCase(intendedFinalDest) && !optDest.equalsIgnoreCase(intermediateTransfer)) {
                return ItineraryValidationResult.mismatch("FINAL_DESTINATION_MISMATCH",
                        String.format("Recovery option destination %s matches neither transfer point (%s) nor final journey destination (%s).",
                                optDest, intermediateTransfer, intendedFinalDest));
            }
        }

        return ItineraryValidationResult.ok();
    }

    /**
     * Enforces Phase 4: Transitions incident to DATA_RECONCILIATION_REQUIRED and invalidates stale options.
     */
    @Transactional
    public void flagReconciliationRequired(RescueCase rescueCase, String mismatchReason) {
        log.error("[Itinerary Integrity] Flagging DATA_RECONCILIATION_REQUIRED for case {}: {}", rescueCase.getId(), mismatchReason);
        rescueCase.setMismatchReason(mismatchReason);

        // Invalidate stale recovery options immediately
        List<RebookOption> staleOptions = optionRepository.findByCaseIdOrderByRankAsc(rescueCase.getId());
        if (!staleOptions.isEmpty()) {
            optionRepository.deleteAll(staleOptions);
            auditService.logEvent(
                    rescueCase.getId(),
                    "RECOVERY_OPTIONS_INVALIDATED",
                    "Flyo Integrity Engine",
                    String.format("Purged %d stale/mismatched options for case %s due to reconciliation failure: %s",
                            staleOptions.size(), rescueCase.getId(), mismatchReason)
            );
        }

        // Invalidate approvals
        rescueCase.setApprovedOptionId(null);
        rescueCase.setApprovedBy(null);
        rescueCase.setApprovedAt(null);

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED,
                "Flyo Itinerary Integrity Validator",
                "Data integrity mismatch detected: " + mismatchReason
        );

        caseRepository.save(rescueCase);

        auditService.logEvent(
                rescueCase.getId(),
                "INCIDENT_ROUTE_MISMATCH",
                "Flyo Itinerary Integrity Validator",
                "Route/Segment integrity check failed: " + mismatchReason
        );

        auditService.logEvent(
                rescueCase.getId(),
                "INCIDENT_RECONCILIATION_REQUIRED",
                "Flyo State Engine",
                "Case locked in DATA_RECONCILIATION_REQUIRED. Automated rebooking, approval, and execution gated."
        );
    }

    /**
     * Phase 4 Server-Side Reconciliation Action.
     */
    @Transactional
    public RescueCase reconcileCase(String caseId, String operator) {
        RescueCase rescueCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        Booking booking = bookingRepository.findById(rescueCase.getBookingId())
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + rescueCase.getBookingId()));

        List<FlightSegment> segments = normalizeAndPersistSegments(booking);
        if (segments.isEmpty()) {
            throw new IllegalStateException("Cannot reconcile case: booking has no flight segments.");
        }

        FlightSegment primarySegment = segments.get(0);

        // Re-align case canonical fields to verified booking and segment
        rescueCase.setPassengerId(booking.getPassengerId());
        rescueCase.setPnr(booking.getPnr());
        rescueCase.setItineraryId(booking.getItineraryId());
        rescueCase.setDisruptedSegmentId(primarySegment.getId());
        rescueCase.setFlightNumber(primarySegment.getFlightNumber());
        rescueCase.setFlight(primarySegment.getFlightNumber());
        rescueCase.setOriginAirport(primarySegment.getOriginAirport());
        rescueCase.setDestinationAirport(primarySegment.getDestinationAirport());
        rescueCase.setScheduledDeparture(primarySegment.getScheduledDeparture());
        rescueCase.setItineraryVersion(booking.getItineraryVersion());
        rescueCase.setFinalDestination(booking.getFinalDestination());
        rescueCase.setOnwardSegmentsCount(segments.size() > 1 ? (segments.size() - 1) : 0);
        rescueCase.setCancelledSegment(primarySegment.getRoute() + " (" + primarySegment.getFlightNumber() + " " + primarySegment.getScheduledDeparture() + ")");
        rescueCase.setMismatchReason(null);

        // Recalculate impact text cleanly
        if (booking.isMultiSegment()) {
            rescueCase.setMissedConnection(booking.getOnwardFlight());
            rescueCase.setImpact(String.format("Flight %s %s cancelled. Misses onward connection: %s.",
                    primarySegment.getFlightNumber(), primarySegment.getRoute(), booking.getOnwardFlight()));
        } else {
            rescueCase.setMissedConnection("None");
            rescueCase.setImpact(String.format("Flight %s %s cancelled. Standalone trip disrupted.",
                    primarySegment.getFlightNumber(), primarySegment.getRoute()));
        }

        // Transition out of DATA_RECONCILIATION_REQUIRED
        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_OPTIONS_READY,
                operator != null && !operator.isBlank() ? operator : "Flyo Reconciliation Engine",
                "Server-side reconciliation successful. Canonical itinerary and segments re-established."
        );

        RescueCase saved = caseRepository.save(rescueCase);

        auditService.logEvent(
                caseId,
                "INCIDENT_SEGMENT_VALIDATED",
                operator != null ? operator : "Flyo Reconciliation Engine",
                String.format("Segment %s (%s %s) validated. Final destination: %s. Version: v%d.",
                        primarySegment.getId(), primarySegment.getFlightNumber(), primarySegment.getRoute(),
                        booking.getFinalDestination(), booking.getItineraryVersion())
        );

        return saved;
    }
}
