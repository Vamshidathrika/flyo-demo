package com.flyo.rescue;

import com.flyo.rescue.controller.MockAirlineController;
import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.PaymentTransactionRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class PaymentSettlementTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CaseService caseService;

    @Autowired
    private PaymentSettlementService paymentService;

    @Autowired
    private VirtualCardRepository cardRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

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

        // Mock Playwright worker rebook response
        RebookResultDto successResult = new RebookResultDto();
        successResult.setSuccess(true);
        successResult.setCaseId("C1");
        successResult.setOptionId("O2");
        successResult.setFlight("FL301");
        successResult.setNewPnr("R7X2K9");

        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(successResult);
        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(successResult);
    }

    @Test
    @DisplayName("Approving Option O2 with fare difference (+₹4,200) automatically issues Corporate VCC")
    void testApproveOptionIssuesVirtualCard() {
        // 1. Simulate cancellation
        caseService.simulateCancellation("B1", "CANCELLED");

        // 2. Rank options
        caseService.generateAndRankOptions("C1");

        // 3. Approve Option O2 (+₹4,200 extra cost)
        caseService.approveOption("C1", "O2", "Flyo Finance Desk");

        // 4. Verify VCC was generated and stored
        Optional<VirtualCard> cardOpt = paymentService.getActiveCardForCase("C1");
        assertTrue(cardOpt.isPresent());
        VirtualCard card = cardOpt.get();
        assertEquals("C1", card.getCaseId());
        assertEquals("O2", card.getOptionId());
        assertEquals(4200, card.getAmount());
        assertEquals("ISSUED", card.getStatus());
        assertTrue(card.getCardNumber().startsWith("453298"));
        assertEquals("12/28", card.getExpiry());
        assertNotNull(card.getCvv());
    }

    @Test
    @DisplayName("Successful rebooking charges VCC and records accounting ledger transaction")
    void testRebookChargesVirtualCardAndRecordsLedger() {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.approveOption("C1", "O2", "Flyo Finance Desk");

        // Execute Rebooking
        RescueCase completed = caseService.rebookCase("C1");
        assertEquals("DONE", completed.getState());
        assertEquals("R7X2K9", completed.getNewPnr());

        // Verify VCC status transitioned to CHARGED
        VirtualCard card = paymentService.getActiveCardForCase("C1").orElseThrow();
        assertEquals("CHARGED", card.getStatus());

        // Verify transaction ledger recorded settlement
        List<PaymentTransaction> ledger = paymentService.getLedgerForCase("C1");
        assertFalse(ledger.isEmpty());
        PaymentTransaction txn = ledger.get(0);
        assertEquals("C1", txn.getCaseId());
        assertEquals(4200, txn.getAmount());
        assertEquals("SETTLED", txn.getStatus());
        assertEquals("INR", txn.getCurrency());
        assertNotNull(txn.getReferenceId());
    }

    @Test
    @DisplayName("GET /api/payments/case/C1 returns Virtual Card and ledger breakdown")
    void testPaymentControllerEndpoint() throws Exception {
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.approveOption("C1", "O2", "Flyo Finance Desk");
        caseService.rebookCase("C1");

        mockMvc.perform(get("/api/payments/case/C1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId", is("C1")))
                .andExpect(jsonPath("$.hasVirtualCard", is(true)))
                .andExpect(jsonPath("$.virtualCard.amount", is(4200)))
                .andExpect(jsonPath("$.virtualCard.status", is("CHARGED")))
                .andExpect(jsonPath("$.transactionCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.transactions[0].status", is("SETTLED")));
    }
}
