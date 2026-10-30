package com.flyo.rescue;

import com.flyo.rescue.controller.MockAirlineController;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.service.CaseService;
import com.flyo.rescue.service.WhatsAppCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class WhatsAppCommandBotTest {

    private static final String OPERATOR_PHONE = "919876543210";
    private static final String UNAUTHORIZED_PHONE = "911234567890";

    @Autowired
    private WhatsAppCommandService commandService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private com.flyo.rescue.repository.RescueCaseRepository caseRepository;

    @Autowired
    private com.flyo.rescue.repository.BookingRepository bookingRepository;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.flyo.rescue.service.RebookService rebookService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        MockAirlineController.setBroken(false);
        commandService.authenticateSessionForTest(OPERATOR_PHONE);

        com.flyo.rescue.dto.RebookResultDto successResult = new com.flyo.rescue.dto.RebookResultDto();
        successResult.setSuccess(true);
        successResult.setCaseId("C1");
        successResult.setOptionId("O2");
        successResult.setFlight("FL301");
        successResult.setNewPnr("R7X2K9");

        com.flyo.rescue.dto.RebookResultDto failureResult = new com.flyo.rescue.dto.RebookResultDto();
        failureResult.setSuccess(false);
        failureResult.setCaseId("C1");
        failureResult.setOptionId("O2");
        failureResult.setFlight("FL301");
        failureResult.setRetryCount(3);
        failureResult.setError("Mock Airline Reservation Gateway Offline (500)");

        org.mockito.Mockito.when(rebookService.executePlaywrightRebook(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    if (MockAirlineController.isBroken()) {
                        return failureResult;
                    }
                    return successResult;
                });

        bookingRepository.findById("B1").ifPresent(b -> {
            b.setStatus("Checked-in");
            bookingRepository.save(b);
        });
        caseRepository.findById("C1").ifPresent(c -> {
            c.setState("DETECTED");
            c.setApprovedOptionId(null);
            c.setApprovedBy(null);
            c.setApprovedAt(null);
            c.setNewPnr(null);
            c.setError(null);
            c.setManualNextStep(null);
            caseRepository.save(c);
        });
    }

    @AfterEach
    void tearDown() {
        MockAirlineController.setBroken(false);
        bookingRepository.findAll().stream()
                .filter(b -> b.getId().startsWith("B-"))
                .forEach(bookingRepository::delete);
    }

    @Test
    @DisplayName("WhatsApp Bot: Unauthorized phone number is rejected with Access Denied")
    void testUnauthorizedNumberRejected() {
        String reply = commandService.processInboundMessage(UNAUTHORIZED_PHONE, "STATUS", null);
        assertTrue(reply.contains("Access Denied"));
        assertTrue(reply.contains("not an authorized Flyo Operator"));
    }

    @Test
    @DisplayName("WhatsApp Bot: HELP command displays full operational menu")
    void testHelpCommand() {
        String reply = commandService.processInboundMessage(OPERATOR_PHONE, "HELP", null);
        assertTrue(reply.contains("Flyo Rescue WhatsApp Control Center"));
        assertTrue(reply.contains("STATUS"));
        assertTrue(reply.contains("DISRUPT"));
        assertTrue(reply.contains("APPROVE"));
        assertTrue(reply.contains("REBOOK"));
    }

    @Test
    @DisplayName("WhatsApp Bot: STATUS command lists active monitored bookings")
    void testStatusCommand() {
        String reply = commandService.processInboundMessage(OPERATOR_PHONE, "STATUS", null);
        assertTrue(reply.contains("Today's Bookings"));
        assertTrue(reply.contains("Ananya Sharma"));
        assertTrue(reply.contains("HYD123"));
    }

    @Test
    @DisplayName("WhatsApp Bot: DISRUPT B1 simulates cancellation and sends ranked options")
    void testDisruptSimulation() {
        String reply = commandService.processInboundMessage(OPERATOR_PHONE, "DISRUPT B1", null);
        assertTrue(reply.contains("CRITICAL DISRUPTION DETECTED"));
        assertTrue(reply.contains("Ananya Sharma"));
        assertTrue(reply.contains("Option O2"));
        assertTrue(reply.contains("Saves Toronto"));

        RescueCase c = caseService.getCaseById("C1").orElseThrow();
        assertTrue("OPTIONS_READY".equals(c.getState()) || "AWAITING_APPROVAL".equals(c.getState()));
        assertEquals("HYD → DEL (AI-839 Tonight 21:00)", c.getCancelledSegment());
    }

    @Test
    @DisplayName("WhatsApp Bot: APPROVE O2 unlocks rebooking gate with WhatsApp Operator actor")
    void testApprovalCommand() {
        // Disruption first
        commandService.processInboundMessage(OPERATOR_PHONE, "DISRUPT B1", null);

        // Approve O2
        String reply = commandService.processInboundMessage(OPERATOR_PHONE, "APPROVE O2", null);
        assertTrue(reply.contains("Option O2 Approved by Flyo Operator"));
        assertTrue(reply.contains("gate is now unlocked"));

        RescueCase c = caseService.getCaseById("C1").orElseThrow();
        assertEquals("O2", c.getApprovedOptionId());
        assertTrue(c.getApprovedBy().contains("Flyo WhatsApp Operator"));
    }

    @Test
    @DisplayName("WhatsApp Bot: REBOOK triggers Playwright worker and confirms PNR R7X2K9")
    void testRebookExecution() {
        // Setup approved case
        commandService.processInboundMessage(OPERATOR_PHONE, "DISRUPT B1", null);
        commandService.processInboundMessage(OPERATOR_PHONE, "APPROVE O2", null);

        // Execute Rebook
        String reply = commandService.processInboundMessage(OPERATOR_PHONE, "REBOOK C1", null);
        assertTrue(reply.contains("ITINERARY SUCCESSFULLY RESCUED"));
        assertTrue(reply.contains("R7X2K9"));
        assertTrue(reply.contains("FL301"));

        RescueCase c = caseService.getCaseById("C1").orElseThrow();
        assertEquals("DONE", c.getState());
        assertEquals("R7X2K9", c.getNewPnr());
    }

    @Test
    @DisplayName("WhatsApp Bot: BREAK PORTAL toggles failure mode and rebook reports 3 retries failure")
    void testBreakPortalViaWhatsApp() {
        commandService.processInboundMessage(OPERATOR_PHONE, "DISRUPT B1", null);
        commandService.processInboundMessage(OPERATOR_PHONE, "APPROVE O2", null);

        // Break portal via WhatsApp chat
        String breakReply = commandService.processInboundMessage(OPERATOR_PHONE, "BREAK PORTAL", null);
        assertTrue(breakReply.contains("BROKEN"));
        assertTrue(MockAirlineController.isBroken());

        // Rebook on broken portal
        String rebookReply = commandService.processInboundMessage(OPERATOR_PHONE, "REBOOK C1", null);
        assertTrue(rebookReply.contains("FAILED"));
        assertTrue(rebookReply.contains("3 of 3 attempts failed"));

        RescueCase c = caseService.getCaseById("C1").orElseThrow();
        assertEquals("FAILED_ESCALATED", c.getState());
        assertNull(c.getNewPnr());

        // Restore portal
        String restoreReply = commandService.processInboundMessage(OPERATOR_PHONE, "RESTORE PORTAL", null);
        assertTrue(restoreReply.contains("OPERATIONAL"));
        assertFalse(MockAirlineController.isBroken());
    }

    @Test
    @DisplayName("WhatsApp Bot: AUTO RESCUE runs full autonomous end-to-end recovery")
    void testAutoRescueMacro() {
        String reply = commandService.processInboundMessage(OPERATOR_PHONE, "AUTO RESCUE B1", null);
        assertTrue(reply.contains("AUTONOMOUS RESCUE COMPLETE"));
        assertTrue(reply.contains("Ananya Sharma"));
        assertTrue(reply.contains("R7X2K9"));

        RescueCase c = caseService.getCaseById("C1").orElseThrow();
        assertEquals("DONE", c.getState());
        assertEquals("R7X2K9", c.getNewPnr());
    }

    @Test
    @DisplayName("WhatsApp Bot: HI greeting presents interactive dashboard operations menu")
    void testHiGreetingPresentsInteractiveDashboardMenu() {
        String reply = commandService.processInboundMessage(OPERATOR_PHONE, "HI", null);
        assertTrue(reply.contains("Welcome to Flyo Rescue Operations Bot"));
        assertTrue(reply.contains("Live Dashboard"));
    }

    @Test
    @DisplayName("WhatsApp Bot: BOOK command dynamically creates booking, ranks flights, and provides booking buttons")
    void testConversationalBookingCommand() {
        String reply = commandService.processInboundMessage(OPERATOR_PHONE, "BOOK BLR BOM Arjun Kapoor", null);
        assertTrue(reply.contains("Flights Found for Arjun Kapoor") || reply.contains("Arjun Kapoor"));
        assertTrue(reply.contains("BLR → BOM"));
        assertTrue(reply.contains("Option O2"));
    }

    @Test
    @DisplayName("WhatsApp Bot: Unauthorized mobile number is rejected on all message types")
    void testUnauthorizedNumberRejectedOnAllMessages() {
        // Any message from unauthorized number must be blocked immediately
        String hiReply = commandService.processInboundMessage(UNAUTHORIZED_PHONE, "HI", null);
        assertTrue(hiReply.contains("Access Denied"));
        assertTrue(hiReply.contains("not an authorized Flyo Operator"));

        String otpReply = commandService.processInboundMessage(UNAUTHORIZED_PHONE, "SEND OTP", null);
        assertTrue(otpReply.contains("Access Denied"));

        String phoneReply = commandService.processInboundMessage(UNAUTHORIZED_PHONE, OPERATOR_PHONE, null);
        assertTrue(phoneReply.contains("Access Denied"));

        String statusReply = commandService.processInboundMessage(UNAUTHORIZED_PHONE, "STATUS", null);
        assertTrue(statusReply.contains("Access Denied"));
    }

    @Test
    @DisplayName("WhatsApp Bot: First HI requires login authentication via phone number and OTP")
    void testFirstHiRequiresLoginAuthenticationWithOtp() {
        // Clear active session to simulate fresh connection
        commandService.clearSessionsForTest();
        assertFalse(commandService.isSessionAuthenticated(OPERATOR_PHONE));

        // 1. First "HI" prompt asks for operator login authentication
        String hiReply = commandService.processInboundMessage(OPERATOR_PHONE, "HI", null);
        assertTrue(hiReply.contains("Operator Authentication Required"));
        assertTrue(hiReply.contains(OPERATOR_PHONE));
        assertTrue(hiReply.contains("Send Login OTP") || hiReply.contains("SEND OTP"));

        // 2. Request OTP
        String otpDispatch = commandService.processInboundMessage(OPERATOR_PHONE, "SEND OTP", null);
        assertTrue(otpDispatch.contains("Flyo Security Verification Code"));
        assertTrue(otpDispatch.contains("single-use 6-digit operator login code"));

        // 3. Extract 6-digit OTP and submit
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(otpDispatch);
        assertTrue(m.find(), "Expected 6-digit OTP code in dispatch message");
        String code = m.group(1);

        String verifyReply = commandService.processInboundMessage(OPERATOR_PHONE, code, null);
        assertTrue(verifyReply.contains("Login Successful"));
        assertTrue(commandService.isSessionAuthenticated(OPERATOR_PHONE));

        // 4. Once authenticated, HI returns active dashboard operations menu
        String welcomeReply = commandService.processInboundMessage(OPERATOR_PHONE, "HI", null);
        assertTrue(welcomeReply.contains("Session Active"));
        assertTrue(welcomeReply.contains("Live Dashboard"));

        // 5. Logout ends session
        String logoutReply = commandService.processInboundMessage(OPERATOR_PHONE, "LOGOUT", null);
        assertTrue(logoutReply.contains("Logged Out"));
        assertFalse(commandService.isSessionAuthenticated(OPERATOR_PHONE));
    }

    @Test
    @DisplayName("WhatsApp Bot: Submitting authorized mobile number triggers login OTP challenge")
    void testDirectPhoneNumberSubmissionTriggersLoginOtp() {
        commandService.clearSessionsForTest();

        // 1. User sends "HI"
        String hiReply = commandService.processInboundMessage(OPERATOR_PHONE, "HI", null);
        assertTrue(hiReply.contains("Operator Authentication Required"));

        // 2. User responds with their registered phone number
        String otpReply = commandService.processInboundMessage(OPERATOR_PHONE, OPERATOR_PHONE, null);
        assertTrue(otpReply.contains("Flyo Security Verification Code"));

        // 3. Extract OTP and authenticate
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(otpReply);
        assertTrue(m.find());
        String code = m.group(1);

        String verifyReply = commandService.processInboundMessage(OPERATOR_PHONE, code, null);
        assertTrue(verifyReply.contains("Login Successful"));
        assertTrue(commandService.isSessionAuthenticated(OPERATOR_PHONE));
    }

    @Test
    @DisplayName("WhatsApp Bot: Unauthenticated operator is blocked from running operational commands")
    void testUnauthenticatedOperatorBlockedFromDashboardCommands() {
        commandService.clearSessionsForTest();

        String statusReply = commandService.processInboundMessage(OPERATOR_PHONE, "STATUS", null);
        assertTrue(statusReply.contains("Operator Authentication Required"));

        String disruptReply = commandService.processInboundMessage(OPERATOR_PHONE, "DISRUPT B1", null);
        assertTrue(disruptReply.contains("Operator Authentication Required"));

        String bookReply = commandService.processInboundMessage(OPERATOR_PHONE, "BOOK BLR BOM Test User", null);
        assertTrue(bookReply.contains("Operator Authentication Required"));
    }
}
