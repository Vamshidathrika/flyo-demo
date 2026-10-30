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

    public static final String STATE_DETECTED = "DETECTED";
    public static final String STATE_OPTIONS_READY = "OPTIONS_READY";
    public static final String STATE_AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String STATE_REBOOKING = "REBOOKING";
    public static final String STATE_DONE = "DONE";
    public static final String STATE_ESCALATED = "ESCALATED";
    public static final String STATE_FAILED_ESCALATED = "FAILED_ESCALATED";

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS;

    static {
        Map<String, Set<String>> map = new HashMap<>();
        map.put(STATE_DETECTED, Set.of(STATE_OPTIONS_READY, STATE_ESCALATED, STATE_AWAITING_APPROVAL));
        map.put(STATE_OPTIONS_READY, Set.of(STATE_OPTIONS_READY, STATE_AWAITING_APPROVAL, STATE_ESCALATED));
        map.put(STATE_AWAITING_APPROVAL, Set.of(STATE_OPTIONS_READY, STATE_REBOOKING, STATE_ESCALATED, STATE_AWAITING_APPROVAL));
        map.put(STATE_REBOOKING, Set.of(STATE_DONE, STATE_FAILED_ESCALATED));
        map.put(STATE_DONE, Collections.emptySet());
        map.put(STATE_ESCALATED, Collections.emptySet());
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
