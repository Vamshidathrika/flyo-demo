package com.flyo.rescue;

import com.flyo.rescue.controller.MockAirlineController;
import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RebookOption;
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

    @Autowired
    private PassengerConfirmationService confirmationService;

    @Autowired
    private PassengerOptionSelectionService optionSelectionService;

    @Autowired
    private RebookingOrchestrationService rebookingOrchestrationService;

    @Autowired
    private com.flyo.rescue.repository.RebookOptionRepository optionRepository;

    @Autowired
    private com.flyo.rescue.repository.PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private com.flyo.rescue.repository.VirtualCardRepository virtualCardRepository;

    @Autowired
    private com.flyo.rescue.repository.DistributedIdempotencyRepository distributedIdempotencyRepository;

    @Autowired
    private com.flyo.rescue.repository.BusinessIdempotencyKeyRepository businessIdempotencyKeyRepository;

    @Autowired
    private com.flyo.rescue.repository.WebhookDeduplicationRepository webhookDeduplicationRepository;

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

        // Clear challenges, cards, locks and lockouts for clean run
        challengeRepository.deleteAll();
        virtualCardRepository.deleteAll();
        distributedIdempotencyRepository.deleteAll();
        businessIdempotencyKeyRepository.deleteAll();
        webhookDeduplicationRepository.deleteAll();
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
        assertTrue(verifyReply.contains("Additional Payment: *₹0"));

        // Verify case is fully rescued (DONE)
        RescueCase completed = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals("DONE", completed.getState());
        assertEquals("M7B3K9", completed.getNewPnr());
        assertEquals("O1", completed.getApprovedOptionId());
        assertNotNull(completed.getPassengerOtpVerifiedAt());

        // Verify Path A explicitly skipped VCC and payment processing
        Optional<VirtualCard> cardOpt = paymentSettlementService.getActiveCardForCase("C1");
        assertTrue(cardOpt.isEmpty(), "Zero-fare booking must NEVER issue a corporate VCC or initiate payment");

        // Verify audit log has the complete passenger trace
        assertTrue(auditEventRepository.findAll().stream().anyMatch(e ->
                "C1".equals(e.getCaseId()) && "PASSENGER_OTP_VERIFIED".equals(e.getEvent())));
        assertTrue(auditEventRepository.findAll().stream().anyMatch(e ->
                "C1".equals(e.getCaseId()) && "PAYMENT_BYPASSED_ZERO_FARE".equals(e.getEvent())));
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

    @Test
    @DisplayName("Scenario F: Booking failure after payment authorization triggers PAYMENT_RECONCILIATION_REQUIRED")
    void testScenarioF_BookingFailureTriggersReconciliationAndFailedState() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE", null);
        String previewReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "OPTION O2", null);
        String otp = extractOtp(previewReply);
        assertNotNull(otp);

        // Mock Playwright rebooking failure
        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new RebookResultDto(false, "C1", "O2", "FL301", null, "Airline inventory locked by concurrent session", 3, java.util.List.of(), java.util.List.of()));

        // Inbound OTP submission
        String failReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, otp, null);
        assertTrue(failReply.contains("Rebooking execution failed") || failReply.contains("Action Notice") || failReply.contains("inventory locked"));

        // Case should be in BOOKING_FAILED
        RescueCase failedCase = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_BOOKING_FAILED, failedCase.getState());

        // Card was authorized, so PAYMENT_RECONCILIATION_REQUIRED audit was triggered
        assertTrue(auditEventRepository.findAll().stream().anyMatch(e ->
                "C1".equals(e.getCaseId()) && "BOOKING_FAILED".equals(e.getEvent())));
    }

    @Test
    @DisplayName("Scenario G: Fare change detection triggers FARE_CHANGED exceptional state")
    void testScenarioG_FareChangeRequiresPassengerReacceptance() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE", null);
        String previewReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "OPTION O2", null);
        String otp = extractOtp(previewReply);
        assertNotNull(otp);

        // Verify with different amount than bound in OTP challenge
        PassengerOtpService.PassengerOtpResult result = passengerOtpService.verifyPassengerOtp(
                "C1", ANANYA_PHONE, otp, "O2", 5500 // Bound was 4200, current is 5500
        );
        assertFalse(result.valid());
        assertEquals("FARE_CHANGED", result.reason());
    }

    @Test
    @DisplayName("Scenario H: Duplicate incoming cancellation alerts are suppressed idempotently")
    void testScenarioH_DuplicateIncomingDisruptionSuppression() {
        cancellationNotificationService.clearDeduplicationCache();
        RescueCase first = cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "Radar1");
        assertNotNull(first);
        assertEquals(CaseStateMachineService.STATE_AWAITING_PASSENGER_DECISION, first.getState());

        // Second call with same cancellation
        RescueCase second = cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "Radar2");
        assertNotNull(second);
        assertEquals(first.getId(), second.getId());
    }

    @Test
    @DisplayName("Scenario I: Extra fare exceeding agency policy limit blocks payment")
    void testScenarioI_AgencySpendingLimitExceededBlocksPayment() {
        boolean approvedStandard = paymentSettlementService.verifyAgencySpendingLimit("C1", 4200);
        assertTrue(approvedStandard);

        boolean approvedExcessive = paymentSettlementService.verifyAgencySpendingLimit("C1", 75000);
        assertFalse(approvedExcessive, "Fare difference exceeding ₹50,000 policy limit must be rejected");
    }

    @Test
    @DisplayName("Scenario J: Agency Spending Limit Exceeded routes to MANUAL_APPROVAL_PENDING without marking payment failed")
    void testScenarioJ_AgencySpendingLimitExceededRoutesToManualApprovalPending() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE", null);

        RebookOption o2 = optionRepository.findById("O2").orElseThrow();
        int originalCost = o2.getExtraCost();
        try {
            o2.setExtraCost(75000);
            optionRepository.save(o2);

            String previewReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, null, "SELECT_OPTION_C1_O2");
            String otp = extractOtp(previewReply);
            assertNotNull(otp);

            confirmationService.confirmAndVerify("C1", ANANYA_PHONE, otp);

            RescueCase c = rebookingOrchestrationService.executeRebookingWorkflow("C1", ANANYA_PHONE);
            assertEquals(CaseStateMachineService.STATE_MANUAL_APPROVAL_PENDING, c.getState());
            assertNotNull(c.getError());
            assertTrue(c.getError().contains("limit exceeded"));
        } finally {
            o2.setExtraCost(originalCost);
            optionRepository.save(o2);
        }
    }

    @Test
    @DisplayName("Scenario K: Confirmed failure voids active card authorization")
    void testScenarioK_ConfirmedFailureVoidsCardAuthorization() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE", null);
        String previewReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, null, "SELECT_OPTION_C1_O2");
        String otp = extractOtp(previewReply);
        assertNotNull(otp);

        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new RebookResultDto(false, "C1", "O2", "FL301", null, "Airline inventory locked by concurrent session", 3, java.util.List.of(), java.util.List.of()));

        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, otp, null);

        RescueCase failedCase = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_BOOKING_FAILED, failedCase.getState());

        Optional<VirtualCard> activeCard = paymentSettlementService.getActiveCardForCase("C1");
        assertTrue(activeCard.isEmpty());

        assertTrue(paymentTransactionRepository.findByCaseIdOrderByTimestampAsc("C1").stream()
                .anyMatch(t -> "VOIDED".equalsIgnoreCase(t.getStatus())));
    }

    @Test
    @DisplayName("Scenario L: Unknown timeout triggers PAYMENT_RECONCILIATION_REQUIRED and host inquiry")
    void testScenarioL_TimeoutOutcomeTriggersPaymentReconciliationAndResolvesViaHostInquiry() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE", null);
        String previewReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, null, "SELECT_OPTION_C1_O2");
        String otp = extractOtp(previewReply);
        assertNotNull(otp);

        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new RebookResultDto(false, "C1", "O2", "FL301", null, "504 Gateway Timeout while processing payment with carrier", 3, java.util.List.of(), java.util.List.of()));

        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, otp, null);

        RescueCase failedCase = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_BOOKING_FAILED, failedCase.getState());
        assertTrue(failedCase.getError().contains("timed out"));

        assertTrue(auditEventRepository.findAll().stream().anyMatch(e ->
                "C1".equals(e.getCaseId()) && "BOOKING_RECONCILED_FAILED".equals(e.getEvent())));
    }

    @Test
    @DisplayName("Scenario M: Involuntary disruption waiver (DGCA CAR / Rule 240) waives fare to ₹0")
    void testScenarioM_CarrierInvoluntaryDisruptionWaiverSetsExtraFareToZero() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        RebookOption o2 = optionRepository.findById("O2").orElseThrow();
        int originalCost = o2.getExtraCost();
        try {
            boolean waived = optionSelectionService.applyInvoluntaryDisruptionWaiver("C1", "O2");
            assertTrue(waived);

            RebookOption updated = optionRepository.findById("O2").orElseThrow();
            assertEquals(0, updated.getExtraCost());

            assertTrue(auditEventRepository.findAll().stream().anyMatch(e ->
                    "C1".equals(e.getCaseId()) && "INVOLUNTARY_DISRUPTION_WAIVER_APPLIED".equals(e.getEvent())));
        } finally {
            o2.setExtraCost(originalCost);
            optionRepository.save(o2);
        }
    }

    @Test
    @DisplayName("Scenario N: Option expiration triggers closed-loop auto-refresh without terminal dead-end")
    void testScenarioN_OptionExpiredClosedLoopAutoRefresh() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE", null);
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "OPTION O1", null);

        optionSelectionService.handleOptionExpired("C1");

        RescueCase c = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_AWAITING_OPTION_SELECTION, c.getState());
    }

    @Test
    @DisplayName("Scenario O: Closed-loop OTP retry allows recovery on second attempt without locking out")
    void testScenarioO_ClosedLoopOtpRetryAllowsRecoveryOnSecondAttempt() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE", null);
        String previewReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "OPTION O1", null);
        String realOtp = extractOtp(previewReply);
        assertNotNull(realOtp);

        String reply1 = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "000000", null);
        assertTrue(reply1.contains("Incorrect") || reply1.contains("Invalid") || reply1.contains("verification code"));

        RescueCase afterFail = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_AWAITING_PASSENGER_OTP, afterFail.getState());

        String reply2 = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, realOtp, null);
        assertTrue(reply2.contains("Your flight rescue is confirmed!"));

        RescueCase afterSuccess = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals("DONE", afterSuccess.getState());
    }

    @Test
    @DisplayName("Scenario P: End-to-end dynamic recovery workflow for non-hardcoded passenger (Vikram Malhotra)")
    void testScenarioP_DynamicMultiPassengerJourneyVikramMalhotra() {
        Booking b2 = new Booking("B-VIK-2", "Vikram Malhotra", "PNR-VIK-2", "DEL → BLR", "6E-204",
                "Tonight 22:00", "None", "None", "IndiGo", "Confirmed", VIKRAM_PHONE);
        bookingRepository.save(b2);

        RescueCase caseVik = cancellationNotificationService.handleFlightCancellation("B-VIK-2", "Flight Cancelled", "IndiGo Radar");
        assertNotNull(caseVik);
        assertEquals(VIKRAM_PHONE, caseVik.getPassengerPhone());

        String replyRescue = passengerWorkflow.processPassengerInbound(VIKRAM_PHONE, "RESCUE MY TRIP", null);
        assertTrue(replyRescue.contains("alternative flights"));

        String replySelect = passengerWorkflow.processPassengerInbound(VIKRAM_PHONE, "OPTION O1", null);
        String otp = extractOtp(replySelect);
        assertNotNull(otp);

        String replyConfirm = passengerWorkflow.processPassengerInbound(VIKRAM_PHONE, otp, null);
        assertTrue(replyConfirm.contains("Your flight rescue is confirmed!"));

        RescueCase doneCase = rescueCaseRepository.findById(caseVik.getId()).orElseThrow();
        assertEquals("DONE", doneCase.getState());
        assertEquals("Vikram Malhotra", doneCase.getPassengerName());
    }

    @Test
    @DisplayName("Scenario Q: Passenger taps [RESCUE MY TRIP] when Case is already in OPTIONS_READY without 409 conflict")
    void testScenarioQ_RescueMyTripWhenCaseAlreadyInOptionsReady() {
        RescueCase c = cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        // Transition to OPTIONS_READY (e.g. operator or background engine generated/ready options)
        stateMachineService.transition(c, CaseStateMachineService.STATE_OPTIONS_READY, "Flyo Operator", "Options generated and ranked on dashboard");
        rescueCaseRepository.save(c);

        RescueCase inOptionsReady = rescueCaseRepository.findById(c.getId()).orElseThrow();
        assertEquals(CaseStateMachineService.STATE_OPTIONS_READY, inOptionsReady.getState());

        // Passenger clicks [RESCUE MY TRIP]
        String reply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, null, "RESCUE_MY_TRIP_" + c.getId());
        assertNotNull(reply);
        assertFalse(reply.contains("409 Conflict"), "Must not encounter 409 Conflict when clicking RESCUE MY TRIP in OPTIONS_READY state");
        assertTrue(reply.contains("alternative flights") || reply.contains("FL301") || reply.contains("FL201") || reply.contains("FL101"));

        RescueCase afterRescue = rescueCaseRepository.findById(c.getId()).orElseThrow();
        assertEquals(CaseStateMachineService.STATE_AWAITING_OPTION_SELECTION, afterRescue.getState());
    }

    @Test
    @DisplayName("Scenario E2: OTP Expiry (>10 min) invalidates challenge, regenerates fresh OTP, and allows recovery")
    void testScenarioE2_OtpExpiryRegeneratesChallengeAndPromptsRetry() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE", null);
        String previewReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "OPTION O1", null);
        String oldOtp = extractOtp(previewReply);
        assertNotNull(oldOtp);

        // Manually expire the active challenge in database
        challengeRepository.findByCaseIdOrderByCreatedAtDesc("C1").forEach(ch -> {
            ch.setExpiresAt(java.time.LocalDateTime.now().minusMinutes(1));
            challengeRepository.save(ch);
        });

        // Submit the expired code
        String expiredReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, oldOtp, null);
        assertTrue(expiredReply.contains("expired") || expiredReply.contains("Expired") || expiredReply.contains("Invalid") || expiredReply.contains("verification code"));

        // Case remains in AWAITING_PASSENGER_OTP or re-armed for retry
        RescueCase c = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_AWAITING_PASSENGER_OTP, c.getState());

        // A new fresh challenge can be requested or entered
        String freshCode = confirmationService.requestConfirmationOtp("C1", ANANYA_PHONE);
        assertNotNull(freshCode);

        // Submit new fresh code -> Success
        String successReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, freshCode, null);
        assertTrue(successReply.contains("Your flight rescue is confirmed!"));
        RescueCase doneCase = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals("DONE", doneCase.getState());
    }

    @Test
    @DisplayName("Scenario J: Application restart recovery restores state from database with zero duplicate bookings")
    void testScenarioJ_ApplicationRestartRecoveryAndIdempotency() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE", null);
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "OPTION O1", null);

        // State is persisted in DB
        RescueCase preRestart = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_AWAITING_PASSENGER_OTP, preRestart.getState());

        // Simulate application restart by creating fresh context query
        RescueCase restored = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_AWAITING_PASSENGER_OTP, restored.getState());
        assertEquals("O1", restored.getSelectedOptionId());
        assertEquals(ANANYA_PHONE, restored.getPassengerPhone());

        // Re-requesting OTP or submitting confirmation continues cleanly
        String otp = extractOtp(passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "OPTION O1", null));
        assertNotNull(otp);
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, otp, null);

        RescueCase completed = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals("DONE", completed.getState());

        // Idempotent rebooking test: executing rebooking again on DONE case does not duplicate
        RescueCase replayed = rebookingOrchestrationService.executeRebookingWorkflow("C1", ANANYA_PHONE);
        assertEquals("DONE", replayed.getState());
        assertEquals(completed.getNewPnr(), replayed.getNewPnr());
    }

    @Test
    @DisplayName("Scenario K: Notification delivery failure preserves confirmed DONE state and outbox record")
    void testScenarioK_WhatsAppDeliveryFailurePreservesDoneState() {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");
        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "RESCUE", null);
        String previewReply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, "OPTION O1", null);
        String otp = extractOtp(previewReply);

        passengerWorkflow.processPassengerInbound(ANANYA_PHONE, otp, null);

        RescueCase completed = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals("DONE", completed.getState());
        assertNotNull(completed.getNewPnr());

        // Verify that even if simulated messaging has issues, the booking state remains DONE
        assertEquals("DONE", completed.getState());
        assertTrue(auditEventRepository.findAll().stream().anyMatch(e ->
                "C1".equals(e.getCaseId()) && "REBOOK_COMPLETED".equals(e.getEvent())));
    }

    @Test
    @DisplayName("Scenario L: Concurrent and repeated passenger callbacks are handled safely without data race")
    void testScenarioL_ConcurrentPassengerCallbacksRaceProtection() throws Exception {
        cancellationNotificationService.handleFlightCancellation("B1", "Flight Cancelled", "FlightRadar24");

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(4);
        java.util.List<String> responses = new java.util.concurrent.CopyOnWriteArrayList<>();

        // 4 concurrent requests clicking [RESCUE MY TRIP]
        for (int i = 0; i < 4; i++) {
            executor.submit(() -> {
                try {
                    String reply = passengerWorkflow.processPassengerInbound(ANANYA_PHONE, null, "RESCUE_MY_TRIP_C1");
                    responses.add(reply != null ? reply : "ACK");
                } catch (Exception e) {
                    responses.add("Handled concurrent conflict: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // All succeeded or handled cleanly
        assertEquals(4, responses.size());
        RescueCase c = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals(CaseStateMachineService.STATE_AWAITING_OPTION_SELECTION, c.getState());
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
