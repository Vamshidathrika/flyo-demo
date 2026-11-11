package com.flyo.rescue.service;

import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative Flight Cancellation Ingress and Proactive Passenger Notification Service.
 *
 * Implements Step 1 & Step 2 of the Passenger-First Rescue Workflow:
 * - Detects flight cancellation
 * - Resolves verified passenger contact
 * - Idempotently creates or updates the recovery case
 * - Prevents duplicate notifications
 * - Dispatches the Step 2 interactive WhatsApp notification ([RESCUE MY TRIP] / [ABANDON RESCUE])
 */
@Service
public class CancellationNotificationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationNotificationService.class);

    private final BookingRepository bookingRepository;
    private final RescueCaseRepository rescueCaseRepository;
    private final CaseService caseService;
    private final CaseStateMachineService stateMachineService;
    private final PassengerWhatsAppMessagingService passengerWhatsAppMessagingService;
    private final AuditService auditService;
    private final DistributedIdempotencyService distributedIdempotencyService;

    public CancellationNotificationService(BookingRepository bookingRepository,
                                         RescueCaseRepository rescueCaseRepository,
                                         CaseService caseService,
                                         CaseStateMachineService stateMachineService,
                                         PassengerWhatsAppMessagingService passengerWhatsAppMessagingService,
                                         AuditService auditService,
                                         DistributedIdempotencyService distributedIdempotencyService) {
        this.bookingRepository = bookingRepository;
        this.rescueCaseRepository = rescueCaseRepository;
        this.caseService = caseService;
        this.stateMachineService = stateMachineService;
        this.passengerWhatsAppMessagingService = passengerWhatsAppMessagingService;
        this.auditService = auditService;
        this.distributedIdempotencyService = distributedIdempotencyService;
    }

    /**
     * Handles an authoritative cancellation event for a booking.
     * Idempotent: returns existing case without sending duplicate notification if already notified.
     */
    @Transactional
    public RescueCase handleFlightCancellation(String bookingId, String disruptionReason, String source) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        // Create or get disruption case in state DETECTED
        RescueCase rescueCase = caseService.simulateCancellation(bookingId, "CANCELLED");

        String paxPhone = booking.getPassengerPhone();
        rescueCase.setPassengerPhone(paxPhone);
        rescueCaseRepository.save(rescueCase);

        if (CaseStateMachineService.STATE_DATA_RECONCILIATION_REQUIRED.equals(rescueCase.getState())) {
            return rescueCase;
        }

        // Distributed Idempotency: prevent duplicate WhatsApp notifications across restarts and multiple workers
        String dedupKey = "CANCELLATION:" + bookingId + ":" + booking.getFlight() + ":CANCELLED";
        boolean acquired = distributedIdempotencyService.tryAcquire(dedupKey, "CANCELLATION_NOTIFICATION", 86400);
        if (acquired || "DETECTED".equals(rescueCase.getState())) {
            // Step 1 & 2: Transition state to PASSENGER_NOTIFIED -> AWAITING_PASSENGER_DECISION
            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_PASSENGER_NOTIFIED,
                    source != null ? source : "Flight Radar Ingress",
                    "Flight " + booking.getFlight() + " cancelled. Dispatching proactive WhatsApp notification to passenger"
            );

            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_AWAITING_PASSENGER_DECISION,
                    "Flyo Messaging Gateway",
                    "Dispatched interactive message with [RESCUE MY TRIP] and [ABANDON RESCUE]"
            );

            rescueCase = rescueCaseRepository.save(rescueCase);

            auditService.logEvent(
                    rescueCase.getId(),
                    "PASSENGER_CANCELLATION_NOTIFIED",
                    source != null ? source : "Flyo Ingress",
                    String.format("Dispatched proactive WhatsApp cancellation alert to %s (%s) for flight %s.",
                            booking.getPassengerName(), paxPhone, booking.getFlight())
            );

            // Send interactive WhatsApp message
            passengerWhatsAppMessagingService.sendCancellationNotification(paxPhone, booking, rescueCase);
            distributedIdempotencyService.complete(dedupKey, "DISPATCHED");
            log.info("[Cancellation Ingress] Notified passenger {} for case {}", paxPhone, rescueCase.getId());
        } else {
            log.info("[Cancellation Ingress] Suppressed duplicate notification for booking {}", bookingId);
        }

        return rescueCase;
    }

    public void clearDeduplicationCache() {
        // No-op for persistent store or purge if required in tests
    }
}
