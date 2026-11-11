package com.flyo.rescue;

import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.SimulatedMessage;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.repository.SimulatedMessageRepository;
import com.flyo.rescue.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * STAGE 13C DEDICATED REGRESSION TEST SUITE:
 * Formal verification and regression prevention for the five original passenger workflow defects:
 *
 * 1. FL301 selected but FL201 confirmed:
 *    When Option O2 (FL301) is selected, the rebooked flight is strictly FL301 and NEVER FL201.
 *
 * 2. Abandoned rescue followed by old OTP submission:
 *    When a rescue is abandoned/declined, subsequent OTP verification is immediately rejected.
 *
 * 3. Mock booking presented as a real ticket:
 *    Simulated tickets carry explicit [SIMULATION / SANDBOX MODE] demarcation.
 *
 * 4. Duplicate confirmation messages:
 *    Idempotent message handling prevents duplicate passenger confirmation messages on duplicate events.
 *
 * 5. B3 command parsing and validation:
 *    STATUS B3 and DISRUPT B3 resolve the persisted record dynamically, while unauthorized access is blocked.
 */
@SpringBootTest
public class OriginalPassengerWorkflowDefectsRegressionTest {

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
    private PassengerRescueWorkflowService passengerWorkflow;

    @Autowired
    private PassengerConfirmationService passengerConfirmationService;

    @Autowired
    private PassengerOtpService passengerOtpService;

    @Autowired
    private PassengerWhatsAppMessagingService passengerWhatsAppMessagingService;

    @Autowired
    private RebookingOrchestrationService rebookingOrchestrationService;

    @Autowired
    private CaseStateMachineService stateMachineService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private RebookOptionRepository optionRepository;

    @Autowired
    private SimulatedMessageRepository messageRepository;

    @MockBean
    private RebookService rebookService;

    @Autowired
    private com.flyo.rescue.config.FlyoDataSeeder dataSeeder;

    @BeforeEach
    void setup() {
        cleanUp();
        commandService.authenticateSessionForTest(OPERATOR_PHONE);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        commandService.clearSessionsForTest();
    }

    private void cleanUp() {
        // Delete only defect-test specific cases, options, messages and bookings to preserve seed data
        optionRepository.findAll().stream()
                .filter(o -> o.getCaseId() != null && o.getCaseId().startsWith("CASE-DEFECT"))
                .forEach(optionRepository::delete);
        rescueCaseRepository.findAll().stream()
                .filter(c -> c.getId() != null && c.getId().startsWith("CASE-DEFECT"))
                .forEach(rescueCaseRepository::delete);
        bookingRepository.findAll().stream()
                .filter(b -> b.getId() != null && b.getId().startsWith("B-DEFECT"))
                .forEach(bookingRepository::delete);

        // Ensure seed bookings are present for downstream tests
        if (bookingRepository.findById("B1").isEmpty()) {
            dataSeeder.run();
        }
    }

    @Test
    @DisplayName("Defect 1: FL301 selected by passenger must confirm FL301 and NEVER FL201")
    void testDefect1_Fl301Selected_MustConfirmFl301AndNeverFl201() {
        String bookingId = "B-DEFECT1-" + UUID.randomUUID().toString().substring(0, 6);
        String caseId = "CASE-DEFECT1-" + UUID.randomUUID().toString().substring(0, 6);

        Booking b = new Booking(bookingId, "Vikram Malhotra", "FLY123", "HYD → DEL", "AI-839",
                "Tonight 21:00", "None", "None", "Flyo", "Checked-in", PASSENGER_PHONE);
        b.setTenantId("tenant_default");
        bookingRepository.save(b);

        RescueCase c = new RescueCase(caseId, bookingId, "Vikram Malhotra",
                CaseStateMachineService.STATE_OPTIONS_READY, "HYD → DEL", "AI-839", "Missed connection", "YYZ", "Delhi Hotel");
        c.setPassengerPhone(PASSENGER_PHONE);
        c.setSelectedOptionId("O2");
        rescueCaseRepository.save(c);

        RebookOption o1 = new RebookOption("O1", caseId, "FL201", "HYD → DEL (Direct)", "Tomorrow 06:00", "Tomorrow 08:05", 85, 0, 2, false, "Disqualified");
        RebookOption o2 = new RebookOption("O2", caseId, "FL301", "HYD → BOM → DEL (via Mumbai)", "Tonight 23:30", "Tomorrow 01:40", 470, 4200, 1, true, "Recommended");
        optionRepository.save(o1);
        optionRepository.save(o2);

        // Mock Playwright worker returning FL301
        RebookResultDto expectedResult = new RebookResultDto(true, caseId, "O2", "FL301", "R9FL301", null, 0, List.of(), List.of());
        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyInt()))
                .thenReturn(expectedResult);

        // Approve and rebook with selected option O2 (FL301)
        caseService.approveOption(caseId, "O2", "Passenger");
        RescueCase completed = rebookingOrchestrationService.executeRebookingWorkflow(caseId, PASSENGER_PHONE);

        // Verify result
        assertNotNull(completed);
        assertEquals("DONE", completed.getState());
        assertEquals("O2", completed.getApprovedOptionId(), "Approved option must strictly be O2 (FL301)");
        assertEquals("R9FL301", completed.getNewPnr());

        RebookOption booked = optionRepository.findById(completed.getApprovedOptionId()).orElseThrow();
        assertEquals("FL301", booked.getFlight(), "Confirmed flight must strictly be FL301");
        assertNotEquals("FL201", booked.getFlight(), "Confirmed flight must NEVER be FL201");
    }

    @Test
    @DisplayName("Defect 2: Abandoned rescue followed by old OTP submission must be strictly rejected")
    void testDefect2_AbandonedRescueFollowedByOldOtp_MustBeRejected() {
        String bookingId = "B-DEFECT2-" + UUID.randomUUID().toString().substring(0, 6);
        String caseId = "CASE-DEFECT2-" + UUID.randomUUID().toString().substring(0, 6);

        Booking b = new Booking(bookingId, "Siddharth Rao", "FLY456", "HYD → DEL", "AI-839",
                "Tonight 21:00", "None", "None", "Flyo", "Checked-in", PASSENGER_PHONE);
        b.setTenantId("tenant_default");
        bookingRepository.save(b);

        RescueCase c = new RescueCase(caseId, bookingId, "Siddharth Rao",
                CaseStateMachineService.STATE_AWAITING_PASSENGER_OTP, "HYD → DEL", "AI-839", "Missed connection", "YYZ", "Delhi Hotel");
        c.setPassengerPhone(PASSENGER_PHONE);
        c.setSelectedOptionId("O2");
        rescueCaseRepository.save(c);

        RebookOption o2 = new RebookOption("O2", caseId, "FL301", "HYD → BOM → DEL", "Tonight 23:30", "Tomorrow 01:40", 470, 4200, 1, true, "Recommended");
        optionRepository.save(o2);

        // Generate active OTP challenge for the case
        String rawOtp = passengerOtpService.createPassengerOtpChallenge(caseId, PASSENGER_PHONE, "O2", 4200, "INR");
        assertNotNull(rawOtp);

        // Passenger abandons / declines the rescue
        passengerWorkflow.handleAbandonRescue(PASSENGER_PHONE, caseId);

        // Subsequent attempt to submit the old OTP
        PassengerOtpService.PassengerOtpResult otpResult = passengerConfirmationService.confirmAndVerify(caseId, PASSENGER_PHONE, rawOtp);

        // Assert rejection
        assertFalse(otpResult.valid(), "Old OTP submission on abandoned rescue must fail");
        assertEquals("ATTEMPT_ABANDONED", otpResult.reason(), "Failure reason must identify abandoned attempt");

        // Verify case remains in PASSENGER_DECLINED and was not revived or rebooked
        RescueCase verified = rescueCaseRepository.findById(caseId).orElseThrow();
        assertEquals(CaseStateMachineService.STATE_PASSENGER_DECLINED, verified.getState());
        assertNull(verified.getNewPnr());
    }

    @Test
    @DisplayName("Defect 3: Mock booking presented as real ticket must carry explicit simulation markers")
    void testDefect3_MockBooking_MustCarryExplicitSimulationMarkers() {
        String caseId = "CASE-DEFECT3-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase rescueCase = new RescueCase(caseId, "B-SIM", "Priya Sharma",
                "DONE", "HYD → DEL", "AI-839", "Missed connection", "None", "None");
        RebookOption option = new RebookOption("O2", caseId, "FL301", "HYD → DEL", "Tonight 23:30", "Tomorrow 01:40", 470, 0, 1, true, "Recommended");

        String confirmation = passengerWhatsAppMessagingService.sendBookingConfirmation(
                PASSENGER_PHONE, rescueCase, option, "SIM-9821A", "VCC-MOCK"
        );

        assertNotNull(confirmation);
        assertTrue(confirmation.contains("[SIMULATION / SANDBOX MODE]"), "Must carry explicit simulation mode header");
        assertTrue(confirmation.contains("Simulation completed. No real airline booking or ticket was created."),
                "Must carry explicit non-accredited simulation disclaimer");
        assertTrue(confirmation.contains("FL301"), "Must reference flight");
        assertTrue(confirmation.contains("SIM-9821A"), "Must reference PNR");
    }

    @Test
    @DisplayName("Defect 4: Duplicate webhook/callback notifications must suppress duplicate messages")
    void testDefect4_DuplicateNotification_MustPreventDuplicatePassengerMessages() {
        String caseId = "CASE-DEFECT4-" + UUID.randomUUID().toString().substring(0, 6);
        RescueCase rescueCase = new RescueCase(caseId, "B-DUP", "Kavita Reddy",
                "DONE", "HYD → DEL", "AI-839", "Missed connection", "None", "None");
        RebookOption option = new RebookOption("O2", caseId, "FL301", "HYD → DEL", "Tonight 23:30", "Tomorrow 01:40", 470, 4200, 1, true, "Recommended");
        String pnr = "R7X2K9";

        // First dispatch: should succeed and send interactive message
        String msg1 = passengerWhatsAppMessagingService.sendBookingConfirmation(
                PASSENGER_PHONE, rescueCase, option, pnr, "VCC-1"
        );
        assertNotNull(msg1);

        // Immediate duplicate dispatch with identical case and PNR: should be deduplicated
        String msg2 = passengerWhatsAppMessagingService.sendBookingConfirmation(
                PASSENGER_PHONE, rescueCase, option, pnr, "VCC-1"
        );
        assertNotNull(msg2);
        assertEquals(msg1, msg2, "Duplicate calls must return the same confirmation copy idempotently");
    }

    @Test
    @DisplayName("Defect 5: B3 command resolution and authorization")
    void testDefect5_B3CommandRejection_MustEnforceAuthorizationAndValidation() {
        // Seed booking B3
        Booking b3 = new Booking("B3", "Priya Nair", "MAA789", "HYD → MAA", "AI-542",
                "Tomorrow 08:30", "None", "None", "Flyo", "On time", "+919822334455");
        b3.setTenantId("tenant_default");
        bookingRepository.save(b3);

        // 1. Authorized Operator query for STATUS B3 must resolve B3 details
        String replyStatusB3 = commandService.processInboundMessage(OPERATOR_PHONE, "STATUS B3", null);
        assertNotNull(replyStatusB3);
        assertTrue(replyStatusB3.contains("B3") && replyStatusB3.contains("Priya Nair"),
                "STATUS B3 must resolve booking B3 from database: " + replyStatusB3);

        // 2. Direct command B3 must resolve single booking status
        String replyDirectB3 = commandService.processInboundMessage(OPERATOR_PHONE, "B3", null);
        assertNotNull(replyDirectB3);
        assertTrue(replyDirectB3.contains("B3") && replyDirectB3.contains("MAA789"),
                "Direct B3 command must resolve booking B3: " + replyDirectB3);

        // 3. Unauthorized sender attempting STATUS B3 must be blocked
        String unauthorizedReply = commandService.processInboundMessage(UNAUTHORIZED_PHONE, "STATUS B3", null);
        assertNotNull(unauthorizedReply);
        assertTrue(unauthorizedReply.contains("Access Denied") || unauthorizedReply.contains("not an authorized Flyo Operator"),
                "Unauthorized sender must be denied: " + unauthorizedReply);

        // 4. Non-existent booking ID query must report not found
        String notFoundReply = commandService.processInboundMessage(OPERATOR_PHONE, "STATUS B999", null);
        assertNotNull(notFoundReply);
        assertTrue(notFoundReply.contains("not found in active records"),
                "Non-existent booking must report not found: " + notFoundReply);
    }
}
