package com.flyo.rescue.service;

import com.flyo.rescue.dto.FlightStatusAlert;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.BookingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class FlightAlertService {

    private final BookingRepository bookingRepository;
    private final CaseService caseService;
    private final AuditService auditService;
    private final AviationRadarClient radarClient;
    private final CanonicalItineraryService canonicalItineraryService;

    public FlightAlertService(BookingRepository bookingRepository,
                              CaseService caseService,
                              AuditService auditService,
                              AviationRadarClient radarClient,
                              CanonicalItineraryService canonicalItineraryService) {
        this.bookingRepository = bookingRepository;
        this.caseService = caseService;
        this.auditService = auditService;
        this.radarClient = radarClient;
        this.canonicalItineraryService = canonicalItineraryService;
    }

    /**
     * Polls the connected aviation radar provider for all currently monitored active passenger flights.
     * Automatically triggers the disruption recovery engine if delays or cancellations are detected.
     */
    @Transactional
    public List<RescueCase> pollAllMonitoredFlights() {
        List<Booking> activeBookings = new ArrayList<>(bookingRepository.findAll());
        activeBookings.sort((a, b) -> {
            if ("B1".equalsIgnoreCase(a.getId())) return -1;
            if ("B1".equalsIgnoreCase(b.getId())) return 1;
            return a.getId().compareToIgnoreCase(b.getId());
        });
        List<RescueCase> triggeredCases = new ArrayList<>();
        Set<String> checkedFlights = new HashSet<>();

        for (Booking booking : activeBookings) {
            String flight = booking.getFlight();
            if (flight != null && !flight.isBlank() && checkedFlights.add(flight) && !"Cancelled".equalsIgnoreCase(booking.getStatus())) {
                Optional<FlightStatusAlert> alertOpt = radarClient.fetchLiveFlightStatus(flight);
                if (alertOpt.isPresent()) {
                    List<RescueCase> cases = handleFlightStatusAlert(alertOpt.get());
                    triggeredCases.addAll(cases);
                }
            }
        }
        triggeredCases.sort((a, b) -> {
            if ("C1".equalsIgnoreCase(a.getId())) return -1;
            if ("C1".equalsIgnoreCase(b.getId())) return 1;
            return a.getId().compareToIgnoreCase(b.getId());
        });
        return triggeredCases;
    }

    /**
     * Receives real-time flight disruption alerts from aviation radar feeds (e.g. FlightAware, Cirium)
     * and automatically triggers the Flyo disruption pipeline for all affected passenger itineraries.
     */
    @Transactional
    public List<RescueCase> handleFlightStatusAlert(FlightStatusAlert alert) {
        if (alert == null || alert.getFlight() == null || alert.getFlight().isBlank()) {
            throw new IllegalArgumentException("Flight status alert must specify a flight number.");
        }

        String targetFlight = normalizeFlight(alert.getFlight());
        String status = alert.getStatus() != null ? alert.getStatus().toUpperCase() : "CANCELLED";
        String source = alert.getSource() != null ? alert.getSource() : "Aviation Radar Feed";

        List<Booking> allBookings = bookingRepository.findAll();
        List<Booking> affectedBookings = new ArrayList<>();

        if (alert.getBookingId() != null && !alert.getBookingId().isBlank()) {
            Booking b = bookingRepository.findById(alert.getBookingId()).orElse(null);
            if (b != null) {
                affectedBookings.add(b);
            }
        } else {
            for (Booking booking : allBookings) {
                if (booking.getFlight() != null && normalizeFlight(booking.getFlight()).equalsIgnoreCase(targetFlight)) {
                    // Check if route matches when alert specifies airport route
                    if (alert.getOriginAirport() != null || alert.getDestinationAirport() != null) {
                        var segOpt = canonicalItineraryService.resolveDisruptedSegment(
                                booking, alert.getFlight(), alert.getOriginAirport(), alert.getDestinationAirport());
                        if (segOpt.isEmpty()) {
                            // Route does not match this passenger's booking - do not falsely disrupt
                            continue;
                        }
                    }
                    affectedBookings.add(booking);
                }
            }
        }

        affectedBookings.sort((a, b) -> {
            if ("B1".equalsIgnoreCase(a.getId())) return -1;
            if ("B1".equalsIgnoreCase(b.getId())) return 1;
            return a.getId().compareToIgnoreCase(b.getId());
        });

        List<RescueCase> createdCases = new ArrayList<>();

        for (Booking booking : affectedBookings) {
            // Trigger Disruption Detection
            RescueCase rescueCase = caseService.simulateCancellation(booking.getId(), status);

            // Validate that alert flight and route actually match this booking
            boolean flightMatches = booking.getFlight() != null && normalizeFlight(booking.getFlight()).equalsIgnoreCase(targetFlight);
            boolean routeMatches = true;
            if (alert.getOriginAirport() != null && !alert.getOriginAirport().isBlank()) {
                routeMatches = alert.getOriginAirport().equalsIgnoreCase(booking.getOriginAirport());
            }
            if (routeMatches && alert.getDestinationAirport() != null && !alert.getDestinationAirport().isBlank()) {
                routeMatches = alert.getDestinationAirport().equalsIgnoreCase(booking.getDestinationAirport());
            }

            if (!flightMatches || !routeMatches) {
                String mismatchReason = !flightMatches
                        ? String.format("Alert flight %s does not match booking %s flight %s.", targetFlight, booking.getId(), booking.getFlight())
                        : String.format("Flight %s matches but alert route %s -> %s does not match booking %s route %s.",
                            targetFlight, alert.getOriginAirport(), alert.getDestinationAirport(), booking.getId(), booking.getRoute());
                canonicalItineraryService.flagReconciliationRequired(rescueCase, mismatchReason);
                createdCases.add(caseService.getCaseById(rescueCase.getId()).orElse(rescueCase));
                continue;
            }

            // If case is in flight / reconciling / terminal, do not re-rank options or force state transition
            if ("RECONCILING".equals(rescueCase.getState()) || "DONE".equals(rescueCase.getState())
                    || "FAILED_ESCALATED".equals(rescueCase.getState()) || "ESCALATED".equals(rescueCase.getState())
                    || CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED.equals(rescueCase.getState())) {
                createdCases.add(rescueCase);
                continue;
            }

            // Immediately rank recovery options
            caseService.generateAndRankOptions(rescueCase.getId());

            // Advance state to AWAITING_APPROVAL / OPTIONS_READY
            caseService.getOptionsForCase(rescueCase.getId());

            // Log Radar Ingestion in Audit Trail
            auditService.logEvent(
                    rescueCase.getId(),
                    "FLIGHT_RADAR_ALERT",
                    source,
                    String.format("Live aviation feed alert: Flight %s marked %s (%s). Delay: %dm. Source: %s.",
                            alert.getFlight(), status,
                            alert.getReason() != null ? alert.getReason() : "Operational Disruption",
                            alert.getDelayMinutes(), source)
            );

            // Re-fetch updated case
            caseService.getCaseById(rescueCase.getId()).ifPresent(createdCases::add);
        }

        return createdCases;
    }

    private String normalizeFlight(String flight) {
        return flight.replaceAll("[-\\s]", "").trim().toUpperCase();
    }
}
