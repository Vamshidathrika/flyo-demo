package com.flyo.rescue.service;

import com.flyo.rescue.exception.IllegalStateTransitionException;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.StateTransition;
import com.flyo.rescue.repository.StateTransitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Formal State Machine Engine for Flyo Disruption Recovery Cases.
 *
 * Enforces strictly valid state transitions across the case lifecycle:
 * DETECTED -> OPTIONS_READY -> AWAITING_APPROVAL -> REBOOKING -> DONE
 *                                                \            \-> FAILED_ESCALATED
 *                                                 \-> ESCALATED
 *
 * Records an immutable transition ledger in state_transitions table,
 * logs audit events, and publishes outbox events.
 */
@Service
public class CaseStateMachineService {

    private static final Logger log = LoggerFactory.getLogger(CaseStateMachineService.class);

    // Target Lifecycle States (Passenger-First Disruption Recovery)
    public static final String STATE_DETECTED = "DETECTED";
    public static final String STATE_PASSENGER_NOTIFIED = "PASSENGER_NOTIFIED";
    public static final String STATE_AWAITING_PASSENGER_DECISION = "AWAITING_PASSENGER_DECISION";
    public static final String STATE_FETCHING_ITINERARY = "FETCHING_ITINERARY";
    public static final String STATE_SEARCHING_ALTERNATIVES = "SEARCHING_ALTERNATIVES";
    public static final String STATE_OPTIONS_READY = "OPTIONS_READY";
    public static final String STATE_AWAITING_OPTION_SELECTION = "AWAITING_OPTION_SELECTION";
    public static final String STATE_OPTION_SELECTED = "OPTION_SELECTED";
    public static final String STATE_REVALIDATING_OPTION = "REVALIDATING_OPTION";
    public static final String STATE_AWAITING_PASSENGER_CONFIRMATION = "AWAITING_PASSENGER_CONFIRMATION";
    public static final String STATE_AWAITING_PASSENGER_OTP = "AWAITING_PASSENGER_OTP";
    public static final String STATE_PASSENGER_VERIFIED = "PASSENGER_VERIFIED";
    public static final String STATE_VERIFYING_AGENCY_APPROVAL = "VERIFYING_AGENCY_APPROVAL";
    public static final String STATE_AGENCY_APPROVAL_REQUIRED = "AGENCY_APPROVAL_REQUIRED";
    public static final String STATE_MANUAL_APPROVAL_PENDING = "MANUAL_APPROVAL_PENDING";
    public static final String STATE_PAYMENT_INSTRUMENT_READY = "PAYMENT_INSTRUMENT_READY";
    public static final String STATE_PAYMENT_AUTH_PENDING = "PAYMENT_AUTH_PENDING";
    public static final String STATE_PAYMENT_AUTHORIZED = "PAYMENT_AUTHORIZED";
    public static final String STATE_PAYMENT_CAPTURED = "PAYMENT_CAPTURED";
    public static final String STATE_PAYMENT_VERIFIED = "PAYMENT_VERIFIED";
    public static final String STATE_REBOOKING = "REBOOKING";
    public static final String STATE_VERIFYING_BOOKING = "VERIFYING_BOOKING";
    public static final String STATE_VERIFYING_TICKET = "VERIFYING_TICKET";
    public static final String STATE_RECONCILING = "RECONCILING";
    public static final String STATE_DONE = "DONE";

    // Operator Approval Bridge (for backwards compatibility & agency console actions)
    public static final String STATE_AWAITING_APPROVAL = "AWAITING_APPROVAL";

    // Exceptional States
    public static final String STATE_PASSENGER_DECLINED = "PASSENGER_DECLINED";
    public static final String STATE_NO_OPTIONS_AVAILABLE = "NO_OPTIONS_AVAILABLE";
    public static final String STATE_ITINERARY_FETCH_FAILED = "ITINERARY_FETCH_FAILED";
    public static final String STATE_REFRESHING_ALTERNATIVES = "REFRESHING_ALTERNATIVES";
    public static final String STATE_OPTION_EXPIRED = "OPTION_EXPIRED";
    public static final String STATE_FARE_CHANGED = "FARE_CHANGED";
    public static final String STATE_OTP_FAILED = "OTP_FAILED";
    public static final String STATE_OTP_EXPIRED = "OTP_EXPIRED";
    public static final String STATE_LOCKED_OUT = "LOCKED_OUT";
    public static final String STATE_PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String STATE_PAYMENT_RECONCILIATION_REQUIRED = "PAYMENT_RECONCILIATION_REQUIRED";
    public static final String STATE_BOOKING_FAILED = "BOOKING_FAILED";
    public static final String STATE_FAILED_ESCALATED = "FAILED_ESCALATED";
    public static final String STATE_MANUAL_INTERVENTION_REQUIRED = "MANUAL_INTERVENTION_REQUIRED";
    public static final String STATE_ESCALATED = "ESCALATED";

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS;

    static {
        Map<String, Set<String>> map = new HashMap<>();

        // DETECTED
        map.put(STATE_DETECTED, Set.of(
                STATE_OPTIONS_READY, STATE_ESCALATED, STATE_AWAITING_APPROVAL,
                STATE_PASSENGER_NOTIFIED, STATE_AWAITING_PASSENGER_DECISION,
                STATE_FETCHING_ITINERARY, STATE_PASSENGER_DECLINED
        ));

        // PASSENGER NOTIFIED
        map.put(STATE_PASSENGER_NOTIFIED, Set.of(
                STATE_AWAITING_PASSENGER_DECISION, STATE_FETCHING_ITINERARY,
                STATE_PASSENGER_DECLINED, STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // AWAITING PASSENGER DECISION (Rescue vs Abandon)
        map.put(STATE_AWAITING_PASSENGER_DECISION, Set.of(
                STATE_FETCHING_ITINERARY, STATE_PASSENGER_DECLINED,
                STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // FETCHING ITINERARY
        map.put(STATE_FETCHING_ITINERARY, Set.of(
                STATE_SEARCHING_ALTERNATIVES, STATE_ITINERARY_FETCH_FAILED,
                STATE_PASSENGER_DECLINED, STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // SEARCHING ALTERNATIVES
        map.put(STATE_SEARCHING_ALTERNATIVES, Set.of(
                STATE_OPTIONS_READY, STATE_NO_OPTIONS_AVAILABLE,
                STATE_PASSENGER_DECLINED, STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // OPTIONS READY
        map.put(STATE_OPTIONS_READY, Set.of(
                STATE_OPTIONS_READY, STATE_AWAITING_APPROVAL, STATE_ESCALATED,
                STATE_AWAITING_OPTION_SELECTION, STATE_OPTION_SELECTED,
                STATE_OPTION_EXPIRED, STATE_NO_OPTIONS_AVAILABLE,
                STATE_PASSENGER_DECLINED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // AWAITING OPTION SELECTION
        map.put(STATE_AWAITING_OPTION_SELECTION, Set.of(
                STATE_OPTION_SELECTED, STATE_OPTIONS_READY, STATE_OPTION_EXPIRED,
                STATE_PASSENGER_DECLINED, STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // OPTION SELECTED
        map.put(STATE_OPTION_SELECTED, Set.of(
                STATE_REVALIDATING_OPTION, STATE_AWAITING_PASSENGER_CONFIRMATION,
                STATE_AWAITING_PASSENGER_OTP, STATE_FARE_CHANGED, STATE_OPTION_EXPIRED,
                STATE_PASSENGER_DECLINED, STATE_ESCALATED
        ));

        // REVALIDATING OPTION
        map.put(STATE_REVALIDATING_OPTION, Set.of(
                STATE_AWAITING_PASSENGER_CONFIRMATION, STATE_AWAITING_PASSENGER_OTP,
                STATE_REBOOKING, STATE_VERIFYING_AGENCY_APPROVAL, STATE_PAYMENT_AUTH_PENDING,
                STATE_FARE_CHANGED, STATE_OPTION_EXPIRED, STATE_PASSENGER_DECLINED, STATE_ESCALATED
        ));

        // AWAITING PASSENGER CONFIRMATION
        map.put(STATE_AWAITING_PASSENGER_CONFIRMATION, Set.of(
                STATE_AWAITING_PASSENGER_OTP, STATE_PASSENGER_VERIFIED,
                STATE_REVALIDATING_OPTION, STATE_OPTION_SELECTED, STATE_FARE_CHANGED,
                STATE_OPTION_EXPIRED, STATE_PASSENGER_DECLINED, STATE_ESCALATED
        ));

        // AWAITING PASSENGER OTP
        map.put(STATE_AWAITING_PASSENGER_OTP, Set.of(
                STATE_PASSENGER_VERIFIED, STATE_OTP_FAILED, STATE_OTP_EXPIRED, STATE_LOCKED_OUT,
                STATE_AWAITING_PASSENGER_OTP, STATE_AWAITING_PASSENGER_CONFIRMATION, STATE_OPTION_EXPIRED,
                STATE_PASSENGER_DECLINED, STATE_ESCALATED
        ));

        // OTP FAILED (allows retry)
        map.put(STATE_OTP_FAILED, Set.of(
                STATE_OTP_FAILED, STATE_AWAITING_PASSENGER_OTP, STATE_LOCKED_OUT, STATE_OTP_EXPIRED, STATE_PASSENGER_DECLINED,
                STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // LOCKED OUT
        map.put(STATE_LOCKED_OUT, Set.of(
                STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED, STATE_FAILED_ESCALATED
        ));

        // OTP EXPIRED
        map.put(STATE_OTP_EXPIRED, Set.of(
                STATE_AWAITING_PASSENGER_CONFIRMATION, STATE_AWAITING_PASSENGER_OTP,
                STATE_AWAITING_OPTION_SELECTION, STATE_OPTIONS_READY,
                STATE_PASSENGER_DECLINED, STATE_ESCALATED
        ));

        // PASSENGER VERIFIED (Branches based on ₹0 vs Positive Fare)
        map.put(STATE_PASSENGER_VERIFIED, Set.of(
                STATE_REVALIDATING_OPTION, STATE_VERIFYING_AGENCY_APPROVAL, STATE_PAYMENT_INSTRUMENT_READY,
                STATE_PAYMENT_AUTH_PENDING, STATE_REBOOKING, STATE_AWAITING_APPROVAL, STATE_ESCALATED, STATE_PASSENGER_DECLINED
        ));

        // VERIFYING AGENCY APPROVAL (Path B)
        map.put(STATE_VERIFYING_AGENCY_APPROVAL, Set.of(
                STATE_PAYMENT_INSTRUMENT_READY, STATE_AGENCY_APPROVAL_REQUIRED, STATE_MANUAL_APPROVAL_PENDING,
                STATE_PAYMENT_AUTH_PENDING, STATE_PAYMENT_FAILED, STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // AGENCY APPROVAL REQUIRED (When spending limit exceeded)
        map.put(STATE_AGENCY_APPROVAL_REQUIRED, Set.of(
                STATE_MANUAL_APPROVAL_PENDING, STATE_PAYMENT_INSTRUMENT_READY, STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // MANUAL APPROVAL PENDING
        map.put(STATE_MANUAL_APPROVAL_PENDING, Set.of(
                STATE_PAYMENT_INSTRUMENT_READY, STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // PAYMENT INSTRUMENT READY (VCC prepared & locked, prior to booking dispatch)
        map.put(STATE_PAYMENT_INSTRUMENT_READY, Set.of(
                STATE_REBOOKING, STATE_PAYMENT_AUTH_PENDING, STATE_ESCALATED, STATE_PASSENGER_DECLINED
        ));

        // PAYMENT AUTH PENDING (Path B)
        map.put(STATE_PAYMENT_AUTH_PENDING, Set.of(
                STATE_PAYMENT_AUTHORIZED, STATE_PAYMENT_CAPTURED, STATE_PAYMENT_VERIFIED, STATE_REBOOKING, STATE_PAYMENT_FAILED,
                STATE_PAYMENT_RECONCILIATION_REQUIRED, STATE_ESCALATED, STATE_PASSENGER_DECLINED
        ));

        // PAYMENT AUTHORIZED
        map.put(STATE_PAYMENT_AUTHORIZED, Set.of(
                STATE_PAYMENT_CAPTURED, STATE_PAYMENT_VERIFIED, STATE_REBOOKING, STATE_VERIFYING_BOOKING,
                STATE_PAYMENT_RECONCILIATION_REQUIRED, STATE_BOOKING_FAILED, STATE_ESCALATED
        ));

        // PAYMENT CAPTURED
        map.put(STATE_PAYMENT_CAPTURED, Set.of(
                STATE_VERIFYING_BOOKING, STATE_VERIFYING_TICKET, STATE_RECONCILING, STATE_DONE,
                STATE_PAYMENT_RECONCILIATION_REQUIRED
        ));

        // PAYMENT VERIFIED (Path B)
        map.put(STATE_PAYMENT_VERIFIED, Set.of(
                STATE_REBOOKING, STATE_PAYMENT_CAPTURED, STATE_PAYMENT_RECONCILIATION_REQUIRED, STATE_ESCALATED
        ));

        // PAYMENT FAILED
        map.put(STATE_PAYMENT_FAILED, Set.of(
                STATE_PAYMENT_AUTH_PENDING, STATE_PAYMENT_RECONCILIATION_REQUIRED,
                STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // PAYMENT RECONCILIATION REQUIRED (Unknown outcome / Timeout)
        map.put(STATE_PAYMENT_RECONCILIATION_REQUIRED, Set.of(
                STATE_RECONCILING, STATE_BOOKING_FAILED, STATE_REBOOKING, STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // RECONCILING (Querying airline host & payment provider to resolve outcome)
        map.put(STATE_RECONCILING, Set.of(
                STATE_VERIFYING_BOOKING, STATE_VERIFYING_TICKET, STATE_DONE, STATE_BOOKING_FAILED,
                STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // AWAITING APPROVAL (Operator Bridge)
        map.put(STATE_AWAITING_APPROVAL, Set.of(
                STATE_OPTIONS_READY, STATE_REBOOKING, STATE_PAYMENT_AUTH_PENDING,
                STATE_ESCALATED, STATE_PASSENGER_DECLINED, STATE_AWAITING_APPROVAL
        ));

        // REBOOKING
        map.put(STATE_REBOOKING, Set.of(
                STATE_PAYMENT_AUTH_PENDING, STATE_PAYMENT_AUTHORIZED, STATE_PAYMENT_CAPTURED, STATE_PAYMENT_VERIFIED,
                STATE_VERIFYING_BOOKING, STATE_VERIFYING_TICKET, STATE_RECONCILING, STATE_DONE, STATE_BOOKING_FAILED,
                STATE_FAILED_ESCALATED, STATE_PAYMENT_RECONCILIATION_REQUIRED
        ));

        // VERIFYING BOOKING
        map.put(STATE_VERIFYING_BOOKING, Set.of(
                STATE_VERIFYING_TICKET, STATE_RECONCILING, STATE_DONE, STATE_BOOKING_FAILED, STATE_FAILED_ESCALATED,
                STATE_PAYMENT_RECONCILIATION_REQUIRED
        ));

        // VERIFYING TICKET
        map.put(STATE_VERIFYING_TICKET, Set.of(
                STATE_RECONCILING, STATE_DONE, STATE_BOOKING_FAILED, STATE_FAILED_ESCALATED,
                STATE_PAYMENT_RECONCILIATION_REQUIRED
        ));

        // BOOKING FAILED
        map.put(STATE_BOOKING_FAILED, Set.of(
                STATE_REBOOKING, STATE_FAILED_ESCALATED, STATE_ESCALATED,
                STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // MANUAL INTERVENTION REQUIRED
        map.put(STATE_MANUAL_INTERVENTION_REQUIRED, Set.of(
                STATE_OPTIONS_READY, STATE_ESCALATED, STATE_DONE
        ));

        // PASSENGER DECLINED
        map.put(STATE_PASSENGER_DECLINED, Set.of(
                STATE_DETECTED, STATE_AWAITING_PASSENGER_DECISION, STATE_OPTIONS_READY, STATE_ESCALATED
        ));

        // NO OPTIONS AVAILABLE
        map.put(STATE_NO_OPTIONS_AVAILABLE, Set.of(
                STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // ITINERARY FETCH FAILED
        map.put(STATE_ITINERARY_FETCH_FAILED, Set.of(
                STATE_ESCALATED, STATE_MANUAL_INTERVENTION_REQUIRED
        ));

        // FARE CHANGED (Loops to new price calculation -> passenger acceptance -> new OTP)
        map.put(STATE_FARE_CHANGED, Set.of(
                STATE_REVALIDATING_OPTION, STATE_AWAITING_PASSENGER_CONFIRMATION, STATE_AWAITING_PASSENGER_OTP,
                STATE_AWAITING_OPTION_SELECTION, STATE_OPTIONS_READY, STATE_OPTION_SELECTED, STATE_ESCALATED
        ));

        // OPTION EXPIRED (Loops to refresh alternatives)
        map.put(STATE_OPTION_EXPIRED, Set.of(
                STATE_REFRESHING_ALTERNATIVES, STATE_SEARCHING_ALTERNATIVES,
                STATE_AWAITING_OPTION_SELECTION, STATE_OPTIONS_READY, STATE_ESCALATED
        ));

        // REFRESHING ALTERNATIVES
        map.put(STATE_REFRESHING_ALTERNATIVES, Set.of(
                STATE_SEARCHING_ALTERNATIVES, STATE_OPTIONS_READY, STATE_NO_OPTIONS_AVAILABLE, STATE_ESCALATED
        ));

        // TERMINAL STATES
        map.put(STATE_DONE, Collections.emptySet());
        map.put(STATE_ESCALATED, Set.of(STATE_DETECTED, STATE_OPTIONS_READY)); // Agency desk can reopen
        map.put(STATE_FAILED_ESCALATED, Collections.emptySet());

        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    private final StateTransitionRepository transitionRepository;
    private final AuditService auditService;
    private final OutboxService outboxService;

    public CaseStateMachineService(StateTransitionRepository transitionRepository,
                                   AuditService auditService,
                                   OutboxService outboxService) {
        this.transitionRepository = transitionRepository;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    public boolean canTransition(String fromState, String toState) {
        if (fromState == null || toState == null) return false;
        if (fromState.equals(toState)) return true; // Idempotent check
        Set<String> allowed = ALLOWED_TRANSITIONS.get(fromState);
        return allowed != null && allowed.contains(toState);
    }

    @Transactional
    public void transition(RescueCase rescueCase, String toState, String actor, String reason) {
        String fromState = rescueCase.getState();
        if (fromState != null && fromState.equals(toState)) {
            // Idempotent transition to same state: record audit but no illegal transition error
            log.info("[StateMachine] Idempotent transition {} -> {} for Case {}", fromState, toState, rescueCase.getId());
            return;
        }

        if (!canTransition(fromState, toState)) {
            String errorMsg = String.format("409 Conflict: Illegal state transition from %s to %s for Case %s. Reason: %s",
                    fromState, toState, rescueCase.getId(), reason);
            log.warn("[StateMachine] {}", errorMsg);
            throw new IllegalStateTransitionException(errorMsg);
        }

        // Apply transition
        rescueCase.setState(toState);
        rescueCase.setUpdatedAt(LocalDateTime.now());

        // 1. Record in immutable StateTransition ledger
        StateTransition transition = new StateTransition(
                rescueCase.getId(),
                fromState,
                toState,
                actor != null ? actor : "Flyo System",
                reason != null ? reason : "State change"
        );
        transitionRepository.save(transition);

        // 2. Append to AuditEvent trail
        auditService.logEvent(
                rescueCase.getId(),
                "STATE_CHANGED",
                actor != null ? actor : "Flyo System",
                String.format("State transitioned %s -> %s. %s", fromState, toState, reason != null ? reason : "")
        );

        // 3. Stage durable OutboxEvent for external listeners
        outboxService.publishEvent(
                "RESCUE_CASE",
                rescueCase.getId(),
                "CASE_STATE_CHANGED",
                String.format("{\"caseId\":\"%s\",\"fromState\":\"%s\",\"toState\":\"%s\",\"actor\":\"%s\"}",
                        rescueCase.getId(), fromState, toState, actor)
        );

        log.info("[StateMachine] Case {} transitioned {} -> {} by {} ({})",
                rescueCase.getId(), fromState, toState, actor, reason);
    }
}
