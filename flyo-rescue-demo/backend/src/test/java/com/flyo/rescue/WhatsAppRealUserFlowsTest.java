package com.flyo.rescue;

import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.SimulatedMessage;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.repository.SimulatedMessageRepository;
import com.flyo.rescue.service.CaseService;
import com.flyo.rescue.service.MessageService;
import com.flyo.rescue.service.PaymentSettlementService;
import com.flyo.rescue.service.RebookService;
import com.flyo.rescue.service.WhatsAppCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
public class WhatsAppRealUserFlowsTest {

    private static final String OPERATOR_PHONE = "919876543210";
    private static final String PASSENGER_PHONE = "919876543211";
    private static final String UNAUTHORIZED_PHONE = "919999999999";

    @Autowired
    private WhatsAppCommandService commandService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private PaymentSettlementService paymentSettlementService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RescueCaseRepository caseRepository;

    @Autowired
    private SimulatedMessageRepository messageRepository;

    @MockBean
    private RebookService rebookService;

    @BeforeEach
    void setUp() {
        caseRepository.findById("C1").ifPresent(c -> {
            c.setState("DETECTED");
            c.setApprovedOptionId(null);
            c.setApprovedBy(null);
            c.setApprovedAt(null);
            c.setNewPnr(null);
            caseRepository.save(c);
        });
        commandService.authenticateSessionForTest(OPERATOR_PHONE);
        RebookResultDto mockResult = new RebookResultDto();
        mockResult.setSuccess(true);
        mockResult.setCaseId("C1");
        mockResult.setOptionId("O2");
        mockResult.setFlight("FL301");
        mockResult.setNewPnr("R7X2K9");

        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString())).thenReturn(mockResult);
        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString())).thenReturn(mockResult);
        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt())).thenReturn(mockResult);
    }

    @AfterEach
    void tearDown() {
        commandService.clearSessionsForTest();
    }

    @Test
    @DisplayName("Operator 1-Tap Workflow: Inbound alert -> APPROVE_REBOOK_O2 -> Settlement card & PNR R7X2K9")
    void testOperatorOneTapApproveAndRebookWorkflow() {
        // Step 1: Simulate cancellation of flight AI-839
        String alertReply = commandService.processInboundMessage(OPERATOR_PHONE, "DISRUPT B1", null);
        assertNotNull(alertReply);
        assertTrue(alertReply.contains("CRITICAL DISRUPTION DETECTED & RESCUE ALERT"));
        assertTrue(alertReply.contains("Option O2 (Rank #1)"));
        assertTrue(alertReply.contains("FL301"));
        assertTrue(alertReply.contains("7h 50m"));
        assertTrue(alertReply.contains("Corporate VCC *7536"));

        // Step 2: Operator taps 1-tap button [⚡ Approve & Rebook O2]
        String settlementReply = commandService.processInboundMessage(OPERATOR_PHONE, "APPROVE_REBOOK_O2", null);
        assertNotNull(settlementReply);
        assertTrue(settlementReply.contains("ITINERARY SUCCESSFULLY RESCUED & SETTLED"));
        assertTrue(settlementReply.contains("R7X2K9"));
        assertTrue(settlementReply.contains("FL301"));
        assertTrue(settlementReply.contains("Flyo VCC *"));
        assertTrue(settlementReply.contains("Ananya Sharma notified with new e-ticket"));

        // Verify case state in database is DONE
        RescueCase c = caseService.getCaseById("C1").orElseThrow();
        assertEquals("DONE", c.getState());
        assertEquals("R7X2K9", c.getNewPnr());
        assertEquals("O2", c.getApprovedOptionId());
    }

    @Test
    @DisplayName("Passenger 3-Stage Care Journey: Stage 1 Reassurance, Stage 2 Confirmed Ticket, Stage 3 Luggage Guide")
    void testPassengerThreeStageCareJourney() {
        // Trigger disruption
        commandService.processInboundMessage(OPERATOR_PHONE, "DISRUPT B1", null);

        // Stage 1 Verification: Proactive reassurance dispatched upon disruption
        List<SimulatedMessage> stage1Msgs = messageRepository.findByCaseIdOrderByTimestampAsc("C1");
        boolean stage1Found = stage1Msgs.stream().anyMatch(m ->
                "PASSENGER_STAGE_1_REASSURANCE".equals(m.getType()) &&
                m.getText().contains("Sri Sai Travels · Priority Travel Desk") &&
                m.getText().contains("Please do not worry about your DEL → Toronto (YYZ)") &&
                m.getText().contains("7 hours and 50 minutes")
        );
        assertTrue(stage1Found, "Stage 1 Reassurance message should be saved for passenger");

        // Execute rebooking to trigger Stage 2
        commandService.processInboundMessage(OPERATOR_PHONE, "APPROVE_REBOOK_O2", null);

        // Stage 2 Verification: Confirmed ticket with PNR R7X2K9 dispatched
        List<SimulatedMessage> stage2Msgs = messageRepository.findByCaseIdOrderByTimestampAsc("C1");
        boolean stage2Found = stage2Msgs.stream().anyMatch(m ->
                "PASSENGER_STAGE_2_TICKET".equals(m.getType()) &&
                m.getText().contains("ITINERARY CONFIRMED • YOU ARE READY TO FLY") &&
                m.getText().contains("R7X2K9") &&
                m.getText().contains("FL301") &&
                m.getText().contains("Terminal 1")
        );
        assertTrue(stage2Found, "Stage 2 Confirmed Ticket message should be saved for passenger");

        // Stage 3 Verification: Passenger clicks [🧳 Luggage Transfer Info] button
        String baggageReply = commandService.processInboundMessage(PASSENGER_PHONE, "PAX_BAGGAGE_INFO", null);
        assertNotNull(baggageReply);
        assertTrue(baggageReply.contains("Transit & Baggage Transfer Guide for Ananya"));
        assertTrue(baggageReply.contains("through-checked directly to Delhi (DEL)"));
        assertTrue(baggageReply.contains("Mumbai (BOM) 45-Minute Transit"));
        assertTrue(baggageReply.contains("airside at Terminal 2"));
        assertTrue(baggageReply.contains("Delhi (DEL) Transfer to International"));

        List<SimulatedMessage> stage3Msgs = messageRepository.findByCaseIdOrderByTimestampAsc("C1");
        boolean stage3Found = stage3Msgs.stream().anyMatch(m ->
                "PASSENGER_STAGE_3_BAGGAGE".equals(m.getType()) &&
                m.getText().contains("Transit & Baggage Transfer Guide")
        );
        assertTrue(stage3Found, "Stage 3 Baggage message should be saved in message repository");
    }

    @Test
    @DisplayName("Dual-Persona Routing: Passenger gets concierge assistance; Unauthorized gets Access Denied")
    void testDualPersonaRouting() {
        // 1. Passenger inquiring about flight status
        String paxStatusReply = commandService.processInboundMessage(PASSENGER_PHONE, "STATUS", null);
        assertNotNull(paxStatusReply);
        assertTrue(paxStatusReply.contains("ITINERARY CONFIRMED • YOU ARE READY TO FLY") ||
                   paxStatusReply.contains("Priority Passenger Concierge"));

        // 2. Passenger greeting / help
        String paxHiReply = commandService.processInboundMessage(PASSENGER_PHONE, "HI", null);
        assertNotNull(paxHiReply);
        assertTrue(paxHiReply.contains("Priority Passenger Concierge"));
        assertTrue(paxHiReply.contains("Ananya"));

        // 3. Unauthorized sender blocked
        String deniedReply = commandService.processInboundMessage(UNAUTHORIZED_PHONE, "STATUS", null);
        assertNotNull(deniedReply);
        assertTrue(deniedReply.contains("Access Denied"));
        assertTrue(deniedReply.contains("not an authorized Flyo Operator"));
    }
}
