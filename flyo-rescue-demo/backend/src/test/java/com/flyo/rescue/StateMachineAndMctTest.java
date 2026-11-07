package com.flyo.rescue;

import com.flyo.rescue.exception.IllegalStateTransitionException;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.StateTransition;
import com.flyo.rescue.repository.StateTransitionRepository;
import com.flyo.rescue.service.CaseStateMachineService;
import com.flyo.rescue.service.MctRulesService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class StateMachineAndMctTest {

    @Autowired
    private MctRulesService mctRulesService;

    @Autowired
    private CaseStateMachineService stateMachineService;

    @Autowired
    private StateTransitionRepository stateTransitionRepository;

    @Test
    @DisplayName("Phase 2: MCT Rules Matrix distinguishes Published Airport MCT from Flyo Conservative Policy")
    void testMctRulesMatrixEvaluation() {
        // Delhi (DEL) Domestic to International
        int publishedDelMct = mctRulesService.getPublishedMctMinutes("DEL", MctRulesService.TransferType.DOMESTIC_TO_INTERNATIONAL);
        int flyoIntlPolicy = mctRulesService.getFlyoPolicyMctMinutes(MctRulesService.TransferType.DOMESTIC_TO_INTERNATIONAL);

        assertEquals(90, publishedDelMct, "Delhi published D-I MCT must be 90 minutes");
        assertEquals(120, flyoIntlPolicy, "Flyo conservative international buffer policy must be 120 minutes");

        // Case A: 85 minutes buffer (e.g. Option O1 direct tomorrow 06:00 -> 08:05, onward at 09:30)
        MctRulesService.MctEvaluation eval85 = mctRulesService.evaluate("DEL", MctRulesService.TransferType.DOMESTIC_TO_INTERNATIONAL, 85);
        assertFalse(eval85.isMeetsPublishedMct(), "85 min buffer fails published MCT (90 min)");
        assertFalse(eval85.isMeetsFlyoPolicy(), "85 min buffer fails Flyo policy (120 min)");
        assertTrue(eval85.getSummary().contains("fails airport published MCT"));

        // Case B: 105 minutes buffer
        MctRulesService.MctEvaluation eval105 = mctRulesService.evaluate("DEL", MctRulesService.TransferType.DOMESTIC_TO_INTERNATIONAL, 105);
        assertTrue(eval105.isMeetsPublishedMct(), "105 min buffer meets airport published MCT (90 min)");
        assertFalse(eval105.isMeetsFlyoPolicy(), "105 min buffer violates Flyo conservative policy (120 min)");
        assertTrue(eval105.getSummary().contains("Disqualified from Rank 1"));

        // Case C: 470 minutes buffer (Option O2 via Mumbai arrives 01:40, onward at 09:30)
        MctRulesService.MctEvaluation eval470 = mctRulesService.evaluate("DEL", MctRulesService.TransferType.DOMESTIC_TO_INTERNATIONAL, 470);
        assertTrue(eval470.isMeetsPublishedMct());
        assertTrue(eval470.isMeetsFlyoPolicy());
        assertTrue(eval470.getSummary().contains("satisfies both"));
    }

    @Test
    @DisplayName("Phase 3: Formal State Machine strictly blocks illegal jumps and records transitions")
    void testFormalStateMachineEnforcement() {
        RescueCase c = new RescueCase("CASE-SM-1", "B-SM-1", "John Doe", "DETECTED", "HYD-DEL", "AI-839", "Impact", "YYZ", "Hotel");
        
        // 1. Legal transition: DETECTED -> OPTIONS_READY
        stateMachineService.transition(c, CaseStateMachineService.STATE_OPTIONS_READY, "Flyo Engine", "Options generated");
        assertEquals(CaseStateMachineService.STATE_OPTIONS_READY, c.getState());

        // 2. Legal transition: OPTIONS_READY -> AWAITING_APPROVAL
        stateMachineService.transition(c, CaseStateMachineService.STATE_AWAITING_APPROVAL, "Flyo Operator", "Option O2 submitted for review");
        assertEquals(CaseStateMachineService.STATE_AWAITING_APPROVAL, c.getState());

        // 3. Illegal jump: AWAITING_APPROVAL -> REBOOKING (Bypassing human approval in APPROVED state is strictly prohibited)
        assertThrows(IllegalStateTransitionException.class, () -> {
            stateMachineService.transition(c, CaseStateMachineService.STATE_REBOOKING, "Flyo Rebook Engine", "Premature dispatch attempt");
        });

        // 4. Legal transition: AWAITING_APPROVAL -> APPROVED (Explicit supervisor approval)
        stateMachineService.transition(c, CaseStateMachineService.STATE_APPROVED, "Flyo Supervisor", "Option O2 approved");
        assertEquals(CaseStateMachineService.STATE_APPROVED, c.getState());

        // 5. Legal transition: APPROVED -> REBOOKING
        stateMachineService.transition(c, CaseStateMachineService.STATE_REBOOKING, "Flyo Rebook Engine", "Playwright worker dispatched");
        assertEquals(CaseStateMachineService.STATE_REBOOKING, c.getState());

        // 6. Legal transition: REBOOKING -> DONE
        stateMachineService.transition(c, CaseStateMachineService.STATE_DONE, "Playwright Worker", "Confirmed PNR received");
        assertEquals(CaseStateMachineService.STATE_DONE, c.getState());

        // 6. Terminal state: DONE cannot transition anywhere
        assertThrows(IllegalStateTransitionException.class, () -> {
            stateMachineService.transition(c, CaseStateMachineService.STATE_REBOOKING, "Hacker", "Replay attack");
        });

        // 7. Verify audit history in state_transitions table
        List<StateTransition> history = stateTransitionRepository.findByCaseIdOrderByTimestampAsc("CASE-SM-1");
        assertFalse(history.isEmpty());
        assertEquals("DETECTED", history.get(0).getFromState());
        assertEquals("OPTIONS_READY", history.get(0).getToState());
    }
}
