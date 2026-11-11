package com.flyo.rescue.service;

import com.flyo.rescue.model.*;
import com.flyo.rescue.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Enterprise Authoritative Multi-Way Booking & Provider Reconciliation Engine.
 *
 * Implements Stage 14 Reconciliation:
 * Compares:
 * 1. Canonical Booking (Itinerary & Segments)
 * 2. Disruption Event (Alert flight & Route)
 * 3. Recovery Option (Route, Fare & Carrier)
 * 4. Provider Execution Record (Order ID, Request ID & Status)
 * 5. Final Confirmed PNR & Electronic Ticket
 *
 * Automatically detects discrepancies and routes conflicting records to DATA_RECONCILIATION_REQUIRED.
 */
@Service
public class AuthoritativeReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(AuthoritativeReconciliationService.class);

    private final BookingRepository bookingRepository;
    private final RescueCaseRepository rescueCaseRepository;
    private final RebookOptionRepository optionRepository;
    private final FlightSegmentRepository segmentRepository;
    private final ProviderReconciliationRepository reconciliationRepository;
    private final CaseStateMachineService stateMachineService;
    private final AuditService auditService;
    private final CanonicalItineraryService canonicalItineraryService;

    public record ReconciliationResult(boolean reconciled, String status, List<String> discrepancies) {
        public static ReconciliationResult clean(String status) {
            return new ReconciliationResult(true, status, List.of());
        }
        public static ReconciliationResult conflict(List<String> discrepancies) {
            return new ReconciliationResult(false, "DATA_RECONCILIATION_REQUIRED", discrepancies);
        }
    }

    public AuthoritativeReconciliationService(BookingRepository bookingRepository,
                                             RescueCaseRepository rescueCaseRepository,
                                             RebookOptionRepository optionRepository,
                                             FlightSegmentRepository segmentRepository,
                                             ProviderReconciliationRepository reconciliationRepository,
                                             CaseStateMachineService stateMachineService,
                                             AuditService auditService,
                                             CanonicalItineraryService canonicalItineraryService) {
        this.bookingRepository = bookingRepository;
        this.rescueCaseRepository = rescueCaseRepository;
        this.optionRepository = optionRepository;
        this.segmentRepository = segmentRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.stateMachineService = stateMachineService;
        this.auditService = auditService;
        this.canonicalItineraryService = canonicalItineraryService;
    }

    /**
     * Executes authoritative multi-way reconciliation for a rescue case.
     */
    @Transactional
    public ReconciliationResult reconcileCase(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            return ReconciliationResult.conflict(List.of("Case ID is null or blank"));
        }

        RescueCase rescueCase = rescueCaseRepository.findById(caseId).orElse(null);
        if (rescueCase == null) {
            return ReconciliationResult.conflict(List.of("Rescue case not found: " + caseId));
        }

        List<String> discrepancies = new ArrayList<>();

        // 1. Validate Underlying Booking
        Booking booking = bookingRepository.findById(rescueCase.getBookingId()).orElse(null);
        if (booking == null) {
            discrepancies.add("Canonical booking missing for booking ID: " + rescueCase.getBookingId());
        }

        // 2. Validate Canonical Flight Segments
        if (booking != null) {
            List<FlightSegment> segments = segmentRepository.findByBookingIdOrderBySegmentIndexAsc(booking.getId());
            if (segments.isEmpty()) {
                segments = canonicalItineraryService.normalizeAndPersistSegments(booking);
            }
            if (segments.isEmpty()) {
                discrepancies.add("Canonical flight segments could not be resolved for booking: " + booking.getId());
            }

            // 3. Disruption Segment vs Booking Identity
            Optional<FlightSegment> disruptedSeg = canonicalItineraryService.resolveDisruptedSegment(
                    booking, rescueCase.getFlightNumber(), rescueCase.getOriginAirport(), rescueCase.getDestinationAirport()
            );
            if (disruptedSeg.isEmpty()) {
                discrepancies.add(String.format("Disrupted flight %s route %s -> %s does not match any segment on booking %s",
                        rescueCase.getFlightNumber(), rescueCase.getOriginAirport(), rescueCase.getDestinationAirport(), booking.getId()));
            }

            // 4. Recovery Option Consistency (If option selected or approved)
            String optId = rescueCase.getApprovedOptionId() != null ? rescueCase.getApprovedOptionId() : rescueCase.getSelectedOptionId();
            if (optId != null) {
                RebookOption option = optionRepository.findById(optId).orElse(null);
                if (option != null) {
                    var optVal = canonicalItineraryService.validateOptionRoute(rescueCase, booking, option);
                    if (!optVal.isValid()) {
                        discrepancies.add("Recovery option route conflict: " + optVal.mismatchReason());
                    }
                }
            }
        }

        // 5. Provider Execution Records Reconciliation
        List<ProviderReconciliationRecord> reconRecords = reconciliationRepository.findByCaseIdOrderByCreatedAtDesc(caseId);
        if (!reconRecords.isEmpty()) {
            ProviderReconciliationRecord latestRecon = reconRecords.get(0);
            if ("TIMEOUT_MANUAL_RECONCILIATION_REQUIRED".equals(latestRecon.getStatus())) {
                discrepancies.add("Unresolved provider timeout requires manual operator desk verification: " + latestRecon.getDiscrepancyDetails());
            }
            if ("RECONCILED".equals(latestRecon.getStatus()) && rescueCase.getNewPnr() != null) {
                if (!rescueCase.getNewPnr().equals(latestRecon.getReconciledPnr())) {
                    discrepancies.add(String.format("PNR mismatch between case (%s) and provider record (%s)",
                            rescueCase.getNewPnr(), latestRecon.getReconciledPnr()));
                }
            }
        }

        // --- Conflict Resolution & State Transition ---
        if (!discrepancies.isEmpty()) {
            String summary = String.join("; ", discrepancies);
            log.error("[Authoritative Reconciliation] Case {} has {} conflicts: {}", caseId, discrepancies.size(), summary);

            rescueCase.setMismatchReason(summary);
            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED,
                    "Flyo Authoritative Reconciliation Engine",
                    "Multi-way reconciliation detected discrepancies: " + summary
            );
            rescueCaseRepository.save(rescueCase);

            auditService.logEvent(
                    caseId,
                    "RECONCILIATION_CONFLICT_DETECTED",
                    "Flyo Reconciliation Engine",
                    "Discrepancies identified: " + summary
            );

            return ReconciliationResult.conflict(discrepancies);
        }

        log.info("[Authoritative Reconciliation] Case {} successfully verified and reconciled.", caseId);
        auditService.logEvent(
                caseId,
                "AUTHORITATIVE_RECONCILIATION_SUCCESS",
                "Flyo Reconciliation Engine",
                "All canonical booking, itinerary segments, options and provider execution records reconciled cleanly."
        );

        return ReconciliationResult.clean("RECONCILED");
    }
}
