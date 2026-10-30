package com.flyo.rescue;

import com.flyo.rescue.controller.MockAirlineController;
import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.repository.AuditEventRepository;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.PassengerOtpChallengeRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
public class WhatsAppPassengerRescueWorkflowTest {

    private static final String ANANYA_PHONE = "+919876543210";
    private static final String VIKRAM_PHONE = "+919811223344";

    @Autowired
    private PassengerRescueWorkflowService passengerWorkflow;

    @Autowired
    private CancellationNotificationService cancellationNotificationService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private CaseStateMachineService stateMachineService;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PassengerOtpChallengeRepository challengeRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private PaymentSettlementService paymentSettlementService;

    @Autowired
    private PassengerOtpService passengerOtpService;

    @MockBean
    private RebookService rebookService;

    @BeforeEach
    void setUp() {
        MockAirlineController.setBroken(false);

        // Mock Playwright Rebook response
        RebookResultDto o1Result = new RebookResultDto();
        o1Result.setSuccess(true);
        o1Result.setCaseId("C1");
        o1Result.setOptionId("O1");
        o1Result.setFlight("FL201");
        o1Result.setNewPnr("M7B3K9");

        RebookResultDto o2Result = new RebookResultDto();
        o2Result.setSuccess(true);
        o2Result.setCaseId("C1");
        o2Result.setOptionId("O2");
        o2Result.setFlight("FL301");
        o2Result.setNewPnr("R7X2K9");

        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String optionId = inv.getArgument(1);
                    if ("O2".equalsIgnoreCase(optionId)) return o2Result;
                    return o1Result;
                });

        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String optionId = inv.getArgument(1);
                    if ("O2".equalsIgnoreCase(optionId)) return o2Result;
                    return o1Result;
                });

        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> {
                    String optionId = inv.getArgument(1);
                    if ("O2".equalsIgnoreCase(optionId)) return o2Result;
                    return o1Result;
                });

        // Seed / Reset Booking B1 for Ananya
        Booking b1 = bookingRepository.findById("B1").orElse(
                new Booking("B1", "PNR-101", "Ananya Sharma", "AI-839", "HYD → DEL",
                        "Tonight 21:00", "Confirmed", "DEL → Toronto (YYZ) Tomorrow 09:30",
                        "Radisson Blu Plaza Delhi (Confirmed)", "Air India", ANANYA_PHONE)
        );
        b1.setStatus("Confirmed");
        b1.setPassengerPhone(ANANYA_PHONE);
        bookingRepository.save(b1);

        // Reset Case C1
        rescueCaseRepository.findById("C1").ifPresent(c -> {
            c.setState("DETECTED");
            c.setPassengerDecision(null);
            c.setSelectedOptionId(null);
            c.setApprovedOptionId(null);
            c.setApprovedBy(null);
            c.setApprovedAt(null);
            c.setNewPnr(null);
            c.setError(null);
            c.setManualNextStep(null);
            c.setPassengerPhone(ANANYA_PHONE);
            c.setPassengerOtpVerifiedAt(null);
            rescueCaseRepository.save(c);
        });

        // Clear challenges and lockouts for clean run
        challengeRepository.deleteAll();
        passengerOtpService.clearLockouts();
    }

    @AfterEach
    void tearDown() {
        MockAirlineController.setBroken(false);
        passengerOtpService.clearLockouts();
    }

    @Test
    @DisplayName("Scenario A: Zero Additional Fare Recovery with Mandatory Passenger OTP")
    void testScenarioA_ZeroAdditionalFareWorkflowWithOtp() {
        // Step 1 & 2: Cancellation detected & proactive WhatsApp alert sent to Ananya
        RescueCase c1 = cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        assertNotNull(c1);
        assertEquals(CaseStateMachineService.STATE_AWAITING_PASSENGER_DECISION, c1.getState());
        assertEquals(ANANYA_PHONE, c1.getPassengerPhone());

        // Passenger clicks [ RESCUE MY TRIP ]
        String rescueReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, null, "RESCUE_MY_TRIP_C1");
        assertTrue(rescueReply.contains("alternative flights") || rescueReply.contains("FL301") || rescueReply.contains("FL201"));

        // Case should now be in AWAITING_OPTION_SELECTION
        RescueCase afterRescue = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_AWAITING_OPTION_SELECTION, afterRescue.getState());
        assertEquals("RESCUE_REQUESTED", afterRescue.getPassengerDecision());

        // Passenger selects Option O1 (₹0 additional fare)
        String optionSelectReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, null, "SELECT_OPTION_C1_O1");
        assertTrue(optionSelectReply.contains("Please review your selected alternative flight."));
        assertTrue(optionSelectReply.contains("Additional Fare: *₹0*"));
        assertTrue(optionSelectReply.contains("Your single-use confirmation code is:"));

        // Extract the generated 6-digit OTP from prompt
        String otp = extractOtp(optionSelectReply);
        assertNotNull(otp, "OTP should be present in message: " + optionSelectReply);
        assertEquals(6, otp.length());

        // Case should now be in AWAITING_PASSENGER_OTP
        RescueCase afterOption = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_AWAITING_PASSENGER_OTP, afterOption.getState());
        assertEquals("O1", afterOption.getSelectedOptionId());

        // Passenger submits 6-digit OTP via WhatsApp
        String verifyReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, otp, null);
        assertTrue(verifyReply.contains("Your flight rescue is confirmed!"));
        assertTrue(verifyReply.contains("M7B3K9"));
        assertTrue(verifyReply.contains("Additional Payment: *₹0*"));

        // Verify case is fully rescued (DONE)
        RescueCase completed = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals("DONE", completed.getState());
        assertEquals("M7B3K9", completed.getNewPnr());
        assertEquals("O1", completed.getApprovedOptionId());
        assertNotNull(completed.getPassengerOtpVerifiedAt());

        // Verify audit log has the complete passenger trace
        assertTrue(auditEventRepository.findAll().stream().anyMatch(e ->
                "C1".equals(e.getCaseId()) && "PASSENGER_OTP_VERIFIED".equals(e.getEvent())));
        assertTrue(auditEventRepository.findAll().stream().anyMatch(e ->
                "C1".equals(e.getCaseId()) && "REBOOK_COMPLETED".equals(e.getEvent())));
    }

    @Test
    @DisplayName("Scenario B: Positive Fare Recovery (+₹4,200) with Mandatory OTP and Payment Authorization")
    void testScenarioB_PositiveFareWorkflowWithOtpAndPaymentAuth() {
        // Step 1 & 2: Cancellation notification
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");

        // Passenger clicks [ RESCUE MY TRIP ]
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE MY TRIP", null);

        // Passenger selects Option O2 (₹4,200 additional fare)
        String optionSelectReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, null, "SELECT_OPTION_C1_O2");
        assertTrue(optionSelectReply.contains("Please review your selected alternative flight."));
        assertTrue(optionSelectReply.contains("4,200") || optionSelectReply.contains("4200"));

        String otp = extractOtp(optionSelectReply);
        assertNotNull(otp);

        // Passenger submits OTP
        String verifyReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, otp, null);
        assertTrue(verifyReply.contains("Your flight rescue is confirmed!"));
        assertTrue(verifyReply.contains("R7X2K9"));
        assertTrue(verifyReply.contains("₹4,200") || verifyReply.contains("4,200"));

        // Verify case state is DONE with Option O2
        RescueCase completed = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals("DONE", completed.getState());
        assertEquals("R7X2K9", completed.getNewPnr());
        assertEquals("O2", completed.getApprovedOptionId());
        assertEquals(4200, completed.getPassengerConfirmedAmount());

        // Verify virtual card was authorized
        Optional<VirtualCard> cardOpt = paymentSettlementService.getActiveCardForCase("C1");
        assertTrue(cardOpt.isPresent());
        assertEquals(4200, cardOpt.get().getAmount());
    }

    @Test
    @DisplayName("Scenario C: Passenger Declines / Abandons Recovery Workflow")
    void testScenarioC_PassengerAbandonsRescue() {
        // Cancellation detected
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");

        // Passenger clicks [ ABANDON RESCUE ]
        String abandonReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, null, "ABANDON_RESCUE_C1");
        assertTrue(abandonReply.contains("You have chosen not to proceed") || abandonReply.contains("Your rescue request has been closed."));

        // Case transitions to PASSENGER_DECLINED
        RescueCase declined = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_PASSENGER_DECLINED, declined.getState());
        assertEquals("ABANDON_RESCUE", declined.getPassengerDecision());
        assertNull(declined.getNewPnr());

        // Original booking is not deleted
        Booking b1 = bookingRepository.findById("B1").orElseThrow();
        assertNotNull(b1);
    }

    @Test
    @DisplayName("Scenario D: Dynamic Passenger Support (Vikram Malhotra) works seamlessly without hardcoding")
    void testScenarioD_DynamicPassengerAndOtherBookings() {
        Booking bDyn = new Booking("B-DYN-1", "Vikram Malhotra", "PNR-DYN-1", "BOM → DEL", "6E-502",
                "Tomorrow 08:00", "None", "None", "IndiGo", "Confirmed", VIKRAM_PHONE);
        bookingRepository.save(bDyn);

        // Cancellation on dynamic booking
        RescueCase caseB2 = cancellationNotificationService.handleFlightCancellation("B-DYN-1", "Bad Weather", "IndiGo Alert");
        assertNotNull(caseB2);
        assertEquals(VIKRAM_PHONE, caseB2.getPassengerPhone());
        assertEquals("Vikram Malhotra", caseB2.getPassengerName());

        // Inbound from Vikram's phone
        String rescueReply = passengerWorkflow.processPassengerInbound(VIKRAM_PHONE, "RESCUE", null);
        assertTrue(rescueReply.contains("alternative flights"));

        // Select option O1
        String selectReply = passengerWorkflow.processPassengerInbound(VIKRAM_PHONE, "OPTION O1", null);
        assertTrue(selectReply.contains("Please review your selected alternative flight."));

        String otp = extractOtp(selectReply);
        assertNotNull(otp);

        // Submit OTP
        String confirmReply = passengerWorkflow.processPassengerInbound(VIKRAM_PHONE, otp, null);
        assertTrue(confirmReply.contains("Your flight rescue is confirmed!"));

        RescueCase b2Completed = rescueCaseRepository.findById(caseB2.getId()).orElseThrow();
        assertEquals("DONE", b2Completed.getState());
        assertEquals("Vikram Malhotra", b2Completed.getPassengerName());
    }

    @Test
    @DisplayName("Scenario E: Security - Invalid OTP decrements attempts, 5 failures triggers lockout & escalation")
    void testScenarioE_InvalidOtpDecrementsAttemptsAndLocksOut() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE", null);
        String previewReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "OPTION O1", null);

        String realOtp = extractOtp(previewReply);
        assertNotNull(realOtp);

        // Attempt 1: wrong OTP
        String fail1 = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "999999", null);
        assertTrue(fail1.contains("Invalid Verification Code") || fail1.contains("does not match"));

        // Attempt 2, 3, 4: wrong OTP
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "999998", null);
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "999997", null);
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "999996", null);

        // Attempt 5: Final failure -> Lockout & Escalation
        String fail5 = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "999995", null);
        assertTrue(fail5.contains("Too Many Attempts") || fail5.contains("temporarily locked") || fail5.contains("Security Notice"));

        // Verify case is escalated
        RescueCase escalated = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_ESCALATED, escalated.getState());

        // Subsequent submission with real OTP is now blocked
        String blockedReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, realOtp, null);
        assertTrue(blockedReply.contains("Too Many Attempts") || blockedReply.contains("temporarily locked")
                || blockedReply.contains("expired") || blockedReply.contains("No active") || blockedReply.contains("Invalid"));
    }

    private String extractOtp(String message) {
        if (message == null) return null;
        Pattern p = Pattern.compile("\\*(\\d{6})\\*");
        Matcher m = p.matcher(message);
        if (m.find()) {
            return m.group(1);
        }
        // Fallback to bare 6-digit match
        Pattern p2 = Pattern.compile("\\b(\\d{6})\\b");
        Matcher m2 = p2.matcher(message);
        if (m2.find()) {
            return m2.group(1);
        }
        return null;
    }
}
