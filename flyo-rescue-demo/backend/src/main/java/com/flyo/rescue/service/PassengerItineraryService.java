package com.flyo.rescue.service;

import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passenger Itinerary Retrieval and Downstream Connection Risk Analysis Service.
 */
@Service
public class PassengerItineraryService {

    private static final Logger log = LoggerFactory.getLogger(PassengerItineraryService.class);

    private final BookingRepository bookingRepository;
    private final RescueCaseRepository rescueCaseRepository;
    private final CaseStateMachineService stateMachineService;
    private final AuditService auditService;
    private final CanonicalItineraryService canonicalItineraryService;

    public PassengerItineraryService(BookingRepository bookingRepository,
                                     RescueCaseRepository rescueCaseRepository,
                                     CaseStateMachineService stateMachineService,
                                     AuditService auditService,
                                     CanonicalItineraryService canonicalItineraryService) {
        this.bookingRepository = bookingRepository;
        this.rescueCaseRepository = rescueCaseRepository;
        this.stateMachineService = stateMachineService;
        this.auditService = auditService;
        this.canonicalItineraryService = canonicalItineraryService;
    }

    /**
     * Retrieves itinerary details, analyzes onward risks, and transitions case to SEARCHING_ALTERNATIVES.
     */
    @Transactional
    public RescueCase fetchAndAnalyzeItinerary(String caseId) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        if (CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED.equals(rescueCase.getState())) {
            log.warn("[PassengerItineraryService] Case {} in DATA_RECONCILIATION_REQUIRED. Itinerary fetch blocked.", caseId);
            return rescueCase;
        }

        Booking booking = bookingRepository.findById(rescueCase.getBookingId())
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + rescueCase.getBookingId()));

        var segments = canonicalItineraryService.normalizeAndPersistSegments(booking);
        var segOpt = canonicalItineraryService.resolveDisruptedSegment(booking, rescueCase.getFlightNumber(), rescueCase.getOriginAirport(), rescueCase.getDestinationAirport());
        if (segOpt.isEmpty()) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, "Disrupted flight segment not found for booking: " + booking.getId());
            return rescueCase;
        }

        var validation = canonicalItineraryService.validateItineraryIntegrity(rescueCase, booking, segOpt.get());
        if (!validation.isValid()) {
            canonicalItineraryService.flagReconciliationRequired(rescueCase, validation.mismatchReason());
            return rescueCase;
        }

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_FETCHING_ITINERARY,
                "Flyo Itinerary Service",
                "Retrieving verified booking details and downstream connection segments"
        );

        // Analyze downstream connection impact
        String onward = booking.getOnwardFlight();
        boolean hasOnward = onward != null && !onward.isBlank() && !onward.equalsIgnoreCase("None");

        String auditDetail = String.format(
                "Itinerary retrieved for %s (PNR %s). Segment: %s %s. Downstream connection: %s.",
                booking.getPassengerName(), booking.getPnr(), booking.getFlight(), booking.getRoute(),
                hasOnward ? onward : "None (Direct itinerary)"
        );

        auditService.logEvent(caseId, "ITINERARY_RETRIEVED", "Flyo Itinerary Service", auditDetail);

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_SEARCHING_ALTERNATIVES,
                "Flyo Itinerary Service",
                "Downstream analysis complete. Initiating candidate flight discovery"
        );

        return rescueCaseRepository.save(rescueCase);
    }
}
