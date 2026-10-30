package com.flyo.rescue;

import com.flyo.rescue.controller.MockAirlineController;
import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.exception.ApprovalRequiredException;
import com.flyo.rescue.exception.IllegalStateTransitionException;
import com.flyo.rescue.model.AuditEvent;
import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.repository.AuditEventRepository;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.repository.VirtualCardRepository;
import com.flyo.rescue.service.CaseService;
import com.flyo.rescue.service.PaymentSettlementService;
import com.flyo.rescue.service.RebookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PRODUCTION ACCEPTANCE STEPS 5 & 6: Financial Safety and Airline Booking Safety Testing.
 *
 * Verifies:
 * 1. Double approval & duplicate rebooking idempotency.
 * 2. Unapproved & escalated rebooking blockage (403 Forbidden).
 * 3. Partner portal failure handling (zero invented PNRs, transition to FAILED_ESCALATED, manual action required).
 * 4. Corporate Treasury VCC controls: single-use issuance, MCC locking, INR currency.
 * 5. Complete sensitive card data isolation (no PAN/CVV leakage in JSON responses).
 */
@SpringBootTest
@AutoConfigureMockMvc
public class FinancialAndBookingSafetyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CaseService caseService;

    @Autowired
    private PaymentSettlementService paymentService;

    @Autowired
    private VirtualCardRepository cardRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @MockBean
    private RebookService rebookService;

    @BeforeEach
    void setUp() {
        MockAirlineController.setBroken(false);

        // Reset B1
        bookingRepository.findById("B1").ifPresent(b -> {
            b.setStatus("Checked-in");
            bookingRepository.save(b);
        });

        // Reset C1
        cardRepository.deleteAll();
        rescueCaseRepository.findById("C1").ifPresent(c -> {
            c.setState("DETECTED");
            c.setApprovedOptionId(null);
            c.setApprovedBy(null);
            c.setApprovedAt(null);
            c.setNewPnr(null);
            c.setError(null);
            c.setManualNextStep(null);
            rescueCaseRepository.save(c);
        });
    }

    @Test
    @DisplayName("Financial Safety 5.1: Sensitive Card Data (PAN, CVV) must NEVER leak via REST API")
    void testSensitiveCardDataNeverExposedInRestApi() throws Exception {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.approveOption("C1", "O2", "Flyo Finance Desk");

        mockMvc.perform(get("/api/payments/case/C1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.virtualCard.cardNumber").doesNotExist())
                .andExpect(jsonPath("$.virtualCard.cvv").doesNotExist())
                .andExpect(jsonPath("$.virtualCard.maskedCardNumber", notNullValue()))
                .andExpect(jsonPath("$.virtualCard.maskedCardNumber", startsWith("•••• •••• •••• ")))
                .andExpect(jsonPath("$.virtualCard.amount", is(4200)))
                .andExpect(jsonPath("$.virtualCard.currency", is("INR")))
                .andExpect(jsonPath("$.virtualCard.merchantCategory", containsString("3000")));
    }

    @Test
    @DisplayName("Financial Safety 5.2: Duplicate approvals are strictly idempotent and avoid duplicate card issuance")
    void testDuplicateApprovalIdempotency() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");

        // First approval
        RescueCase firstApproval = caseService.approveOption("C1", "O2", "Flyo Finance Desk");
        assertEquals("AWAITING_APPROVAL", firstApproval.getState());
        assertEquals("O2", firstApproval.getApprovedOptionId());

        long cardCountAfterFirst = cardRepository.count();

        // Second duplicate approval with same option
        RescueCase secondApproval = caseService.approveOption("C1", "O2", "Flyo Finance Desk");
        assertEquals("AWAITING_APPROVAL", secondApproval.getState());

        long cardCountAfterSecond = cardRepository.count();
        assertEquals(cardCountAfterFirst, cardCountAfterSecond, "Duplicate approval must NOT issue duplicate Virtual Card");
    }

    @Test
    @DisplayName("Financial Safety 5.3: Zero extra cost options do NOT issue unnecessary virtual cards")
    void testZeroCostOptionDoesNotIssueCard() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");

        // Option O1 has 0 extra cost
        caseService.approveOption("C1", "O1", "Flyo Operator");

        Optional<VirtualCard> card = paymentService.getActiveCardForCase("C1");
        assertTrue(card.isEmpty(), "Zero extra cost option must not issue virtual commercial card");
    }

    @Test
    @DisplayName("Booking Safety 6.1: Rebooking attempt without prior human approval throws 403 Forbidden")
    void testRebookWithoutApprovalBlocked() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");

        assertThrows(ApprovalRequiredException.class, () -> caseService.rebookCase("C1"));

        RescueCase c = rescueCaseRepository.findById("C1").orElseThrow();
        assertNotEquals("DONE", c.getState());
        assertNull(c.getNewPnr());
    }

    @Test
    @DisplayName("Booking Safety 6.2: Rebooking attempt on ESCALATED case throws 403 Forbidden")
    void testRebookOnEscalatedCaseBlocked() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.rejectCase("C1", "Passenger requested full refund", "Flyo Operator");

        assertThrows(ApprovalRequiredException.class, () -> caseService.rebookCase("C1"));

        RescueCase c = rescueCaseRepository.findById("C1").orElseThrow();
        assertEquals("ESCALATED", c.getState());
        assertNull(c.getNewPnr());
    }

    @Test
    @DisplayName("Booking Safety 6.3: Approval attempt on ESCALATED case throws 409 Conflict")
    void testApproveOnEscalatedCaseBlocked() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.rejectCase("C1", "Passenger requested full refund", "Flyo Operator");

        assertThrows(IllegalStateTransitionException.class, () ->
                caseService.approveOption("C1", "O2", "Flyo Operator"));
    }

    @Test
    @DisplayName("Booking Safety 6.4: Mock airline portal failure results in FAILED_ESCALATED with zero invented PNR")
    void testAirlineFailureTransitionsToFailedEscalatedWithZeroInventedPnr() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.approveOption("C1", "O2", "Flyo Operator");

        // Mock Playwright worker returning failure (simulating airline 500 error / portal down)
        RebookResultDto failureResult = new RebookResultDto();
        failureResult.setSuccess(false);
        failureResult.setCaseId("C1");
        failureResult.setOptionId("O2");
        failureResult.setFlight("FL301");
        failureResult.setNewPnr(null);
        failureResult.setError("Airline server returned HTTP 500 Internal Error during seat allocation");
        failureResult.setRetryCount(3);

        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(failureResult);

        RescueCase failedCase = caseService.rebookCase("C1");

        assertEquals("FAILED_ESCALATED", failedCase.getState(), "Failed rebooking must transition to FAILED_ESCALATED");
        assertNull(failedCase.getNewPnr(), "FAILED rebooking must NEVER invent a PNR");
        assertNotNull(failedCase.getError());
        assertTrue(failedCase.getError().contains("HTTP 500"));
        assertNotNull(failedCase.getManualNextStep());
        assertTrue(failedCase.getManualNextStep().contains("Manual Action Required"));

        // Audit Trail check
        List<AuditEvent> audits = auditEventRepository.findByCaseIdOrderByTimestampAsc("C1");
        boolean hasFailureAudit = audits.stream().anyMatch(a -> "REBOOK_FAILED".equals(a.getEvent()));
        assertTrue(hasFailureAudit, "REBOOK_FAILED audit event must be logged with retry count and error reason");
    }

    @Test
    @DisplayName("Booking Safety 6.5: Successful rebooking transitions to DONE and updates monitored booking")
    void testSuccessfulRebookingTransitionsToDone() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.approveOption("C1", "O2", "Flyo Operator");

        RebookResultDto successResult = new RebookResultDto();
        successResult.setSuccess(true);
        successResult.setCaseId("C1");
        successResult.setOptionId("O2");
        successResult.setFlight("FL301");
        successResult.setNewPnr("R7X2K9");

        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(successResult);

        RescueCase doneCase = caseService.rebookCase("C1");

        assertEquals("DONE", doneCase.getState());
        assertEquals("R7X2K9", doneCase.getNewPnr());
        assertNull(doneCase.getError());

        // Monitored booking should be marked 'Rescued'
        assertEquals("Rescued", bookingRepository.findById("B1").orElseThrow().getStatus());

        // Virtual card charged and ledger updated
        VirtualCard card = paymentService.getActiveCardForCase("C1").orElseThrow();
        assertEquals("CHARGED", card.getStatus());

        List<PaymentTransaction> ledger = paymentService.getLedgerForCase("C1");
        assertFalse(ledger.isEmpty());
        assertEquals("SETTLED", ledger.get(0).getStatus());
    }
}
