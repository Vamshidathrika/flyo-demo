package com.flyo.rescue;

import com.flyo.rescue.controller.MockAirlineController;
import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.model.AuditEvent;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.AuditEventRepository;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.service.CaseService;
import com.flyo.rescue.service.RebookService;
import com.flyo.rescue.service.WhatsAppCommandService;
import com.flyo.rescue.service.WhatsAppOtpService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
public class WhatsAppOtpSecurityTest {

    private static final String OPERATOR_PHONE = "919876543210";

    @Autowired
    private WhatsAppCommandService commandService;

    @Autowired
    private WhatsAppOtpService otpService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private RescueCaseRepository caseRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @MockBean
    private RebookService rebookService;

    @BeforeEach
    void setUp() {
        MockAirlineController.setBroken(false);
        commandService.authenticateSessionForTest(OPERATOR_PHONE);
        otpService.clear(OPERATOR_PHONE);

        RebookResultDto successResult = new RebookResultDto();
        successResult.setSuccess(true);
        successResult.setCaseId("C1");
        successResult.setOptionId("O2");
        successResult.setFlight("FL301");
        successResult.setNewPnr("R7X2K9");

        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString())).thenReturn(successResult);

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
        otpService.clear(OPERATOR_PHONE);
    }

    @Test
    @DisplayName("Security: REQUEST OTP generates 6-digit challenge and delivers via WhatsApp chat")
    void testOtpDispatchedViaWhatsAppChat() {
        // 1. Simulate disruption first
        commandService.processInboundMessage(OPERATOR_PHONE, "DISRUPT B1", null);

        // 2. Request OTP via chat
        String reply = commandService.processInboundMessage(OPERATOR_PHONE, "REQUEST OTP", null);
        assertTrue(reply.contains("Flyo Security Verification Required"));
        assertTrue(reply.contains("single-use security authorization code"));

        // 3. Verify OTP challenge exists in OtpService
        Optional<WhatsAppOtpService.OtpChallenge> opt = otpService.getActiveOtp(OPERATOR_PHONE);
        assertTrue(opt.isPresent());
        assertEquals("C1", opt.get().caseId());
        assertEquals("O2", opt.get().optionId());
        assertNotNull(opt.get().code());
        assertEquals(6, opt.get().code().length());
        assertTrue(reply.contains(opt.get().code()));
    }

    @Test
    @DisplayName("Security: Invalid OTP is rejected and rebooking gate remains locked")
    void testInvalidOtpRejected() {
        commandService.processInboundMessage(OPERATOR_PHONE, "DISRUPT B1", null);
        commandService.processInboundMessage(OPERATOR_PHONE, "REQUEST OTP", null);

        // Submit invalid OTP code
        String reply = commandService.processInboundMessage(OPERATOR_PHONE, "OTP 000000", null);
        assertTrue(reply.contains("Security Verification Failed"));
        assertTrue(reply.contains("rebooking gate remains locked"));

        // Verify case is NOT approved
        RescueCase c = caseService.getCaseById("C1").orElseThrow();
        assertNull(c.getApprovedOptionId());
    }

    @Test
    @DisplayName("Security: Valid OTP verified via WhatsApp chat approves option, unlocks gate, and logs audit")
    void testValidOtpApprovesOptionAndUnlocksGate() {
        commandService.processInboundMessage(OPERATOR_PHONE, "DISRUPT B1", null);
        commandService.processInboundMessage(OPERATOR_PHONE, "REQUEST OTP", null);

        WhatsAppOtpService.OtpChallenge challenge = otpService.getActiveOtp(OPERATOR_PHONE).orElseThrow();
        String validCode = challenge.code();

        // Submit valid OTP via WhatsApp chat
        String reply = commandService.processInboundMessage(OPERATOR_PHONE, "OTP " + validCode, null);
        assertTrue(reply.contains("Security Authorization Verified"));
        assertTrue(reply.contains("Option O2 Approved by Flyo Operator"));
        assertTrue(reply.contains("gate is now unlocked"));

        // Verify case is approved
        RescueCase c = caseService.getCaseById("C1").orElseThrow();
        assertEquals("O2", c.getApprovedOptionId());
        assertTrue(c.getApprovedBy().contains("OTP Verified"));

        // Verify audit event
        List<AuditEvent> events = auditEventRepository.findByCaseIdOrderByTimestampAsc("C1");
        boolean hasOtpEvent = events.stream().anyMatch(e -> "OPERATOR_OTP_VERIFIED".equals(e.getEvent()));
        assertTrue(hasOtpEvent, "Expected OPERATOR_OTP_VERIFIED in audit trail");

        // Execute rebook
        String rebookReply = commandService.processInboundMessage(OPERATOR_PHONE, "REBOOK C1", null);
        assertTrue(rebookReply.contains("ITINERARY SUCCESSFULLY RESCUED"));
        assertTrue(rebookReply.contains("R7X2K9"));

        RescueCase rescued = caseService.getCaseById("C1").orElseThrow();
        assertEquals("DONE", rescued.getState());
        assertEquals("R7X2K9", rescued.getNewPnr());
    }

    @Test
    @DisplayName("Real-World Flow: Simulate Cancel -> WhatsApp Push with Do-ables -> Rebook Blocked Without Auth -> Request OTP -> Enter OTP in Chat -> Final Auto-Rebook Executed")
    void testRealWorldCancellationTriggerAndChatOtpAuthenticationFlow() {
        // Step 1: Cancellation trigger happens (e.g. DISRUPT B1 or simulated via UI)
        String disruptReply = commandService.processInboundMessage(OPERATOR_PHONE, "DISRUPT B1", null);
        
        // Step 2: Message pushed to WhatsApp shows do-ables (rebooking options)
        assertTrue(disruptReply.contains("CRITICAL DISRUPTION DETECTED"));
        assertTrue(disruptReply.contains("Ananya Sharma"));
        assertTrue(disruptReply.contains("Option O2"));
        assertTrue(disruptReply.contains("Option O3"));
        assertTrue(disruptReply.contains("Option O1"));
        assertTrue(disruptReply.contains("Reject & Escalate"));

        // Step 3: Rule enforced: Final rebook CANNOT be done before authentication
        String blockedRebook = commandService.processInboundMessage(OPERATOR_PHONE, "REBOOK C1", null);
        assertTrue(blockedRebook.contains("Authentication Required") || blockedRebook.contains("Security Verification"));

        // Step 4: Operator selects Option O2 in chat -> system asks for OTP in chat itself
        String otpChallengeReply = commandService.processInboundMessage(OPERATOR_PHONE, "SELECT O2", null);
        assertTrue(otpChallengeReply.contains("Flyo Security Verification Required"));
        assertTrue(otpChallengeReply.contains("single-use security authorization code"));

        Optional<WhatsAppOtpService.OtpChallenge> opt = otpService.getActiveOtp(OPERATOR_PHONE);
        assertTrue(opt.isPresent(), "Expected active OTP challenge generated in chat");
        String otpCode = opt.get().code();

        // Step 5: Operator gives the OTP in chat itself
        String finalRescueReply = commandService.processInboundMessage(OPERATOR_PHONE, "OTP " + otpCode, null);

        // Step 6: Final execution is verified only after authentication
        assertTrue(finalRescueReply.contains("Security Authorization Verified"));
        assertTrue(finalRescueReply.contains("ITINERARY SUCCESSFULLY RESCUED"));
        assertTrue(finalRescueReply.contains("R7X2K9"));
        assertTrue(finalRescueReply.contains("FL301"));

        RescueCase doneCase = caseService.getCaseById("C1").orElseThrow();
        assertEquals("DONE", doneCase.getState());
        assertEquals("R7X2K9", doneCase.getNewPnr());
        assertTrue(doneCase.getApprovedBy().contains("OTP Verified"));
    }

    @Test
    @DisplayName("Security: 5 consecutive invalid OTP attempts locks out the phone number for 15 minutes")
    void testBruteForceLockout() {
        otpService.createOtpChallenge(OPERATOR_PHONE, "C1", "O2");
        assertFalse(otpService.isLockedOut(OPERATOR_PHONE));

        // Submit 5 wrong OTPs
        for (int i = 0; i < 5; i++) {
            Optional<WhatsAppOtpService.OtpChallenge> res = otpService.verifyOtp(OPERATOR_PHONE, "000000");
            assertTrue(res.isEmpty());
        }

        // Phone is now locked out
        assertTrue(otpService.isLockedOut(OPERATOR_PHONE));

        // Creating challenge when locked out throws IllegalStateException
        assertThrows(IllegalStateException.class, () -> {
            otpService.createOtpChallenge(OPERATOR_PHONE, "C1", "O2");
        });
    }
}
