package com.flyo.rescue;

import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.repository.PaymentTransactionRepository;
import com.flyo.rescue.repository.VirtualCardRepository;
import com.flyo.rescue.security.TenantContext;
import com.flyo.rescue.service.PaymentSettlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class Stage6PaymentAndReconciliationTest {

    @Autowired
    private PaymentSettlementService paymentService;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private VirtualCardRepository cardRepository;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("tenant_corporate_acct");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Payment 6.1: Authorization and capture lifecycle separation")
    void testAuthAndCaptureSeparation() {
        String caseId = "CASE-PAY-" + System.currentTimeMillis();
        String idemKeyAuth = "idem_auth_" + System.currentTimeMillis();

        // 1. Authorize Hold
        PaymentTransaction authTxn = paymentService.authorizePayment(
                caseId, "OPT-1", 4500, "INR", "Rohan Mehta", idemKeyAuth
        );

        assertNotNull(authTxn);
        assertEquals(PaymentTransaction.STATUS_AUTHORIZED, authTxn.getStatus());
        assertEquals(PaymentTransaction.TYPE_AUTH, authTxn.getTransactionType());
        assertEquals(4500, authTxn.getAmount());
        assertFalse(authTxn.getReconciled());
        assertNotNull(authTxn.getVirtualCardId());

        // 2. Capture Settled Amount
        String idemKeyCap = "idem_cap_" + System.currentTimeMillis();
        PaymentTransaction capTxn = paymentService.capturePayment(
                caseId, authTxn.getVirtualCardId(), 4500, "INR", "GDS-SETTLE-7788", idemKeyCap
        );

        assertNotNull(capTxn);
        assertEquals(PaymentTransaction.STATUS_CAPTURED, capTxn.getStatus());
        assertEquals(PaymentTransaction.TYPE_CAPTURE, capTxn.getTransactionType());
        assertEquals(4500, capTxn.getAmount());

        // Verify card status is updated to SETTLED
        VirtualCard card = cardRepository.findById(authTxn.getVirtualCardId()).orElseThrow();
        assertEquals("SETTLED", card.getStatus());
    }

    @Test
    @DisplayName("Payment 6.2: Idempotency keys prevent duplicate authorizations and captures")
    void testPaymentIdempotencyDeduplication() {
        String caseId = "CASE-IDEM-" + System.currentTimeMillis();
        String idemKey = "fixed_idem_key_" + caseId;

        // First call
        PaymentTransaction txn1 = paymentService.authorizePayment(
                caseId, "OPT-1", 3000, "INR", "Sonia Patel", idemKey
        );

        // Second duplicate call with same idempotency key
        PaymentTransaction txn2 = paymentService.authorizePayment(
                caseId, "OPT-1", 3000, "INR", "Sonia Patel", idemKey
        );

        // Must return identical transaction without creating a duplicate record
        assertEquals(txn1.getId(), txn2.getId(), "Duplicate call must return existing transaction");
        assertEquals(1, transactionRepository.findByCaseIdOrderByTimestampAsc(caseId).size(),
                "Exactly 1 transaction record must exist for this idempotency key");
    }

    @Test
    @DisplayName("Payment 6.3: Currency precision & ISO 4217 validation")
    void testCurrencyValidation() {
        String caseId = "CASE-CURR-" + System.currentTimeMillis();

        // Negative amount rejected
        assertThrows(IllegalArgumentException.class, () ->
                paymentService.authorizePayment(caseId, "OPT-1", -500, "INR", "Pax", "k1"));

        // Zero amount rejected
        assertThrows(IllegalArgumentException.class, () ->
                paymentService.authorizePayment(caseId, "OPT-1", 0, "INR", "Pax", "k2"));

        // Unsupported fictitious currency code rejected
        assertThrows(IllegalArgumentException.class, () ->
                paymentService.authorizePayment(caseId, "OPT-1", 1000, "XYZ_FAKE", "Pax", "k3"));

        // Valid supported currencies accepted
        assertDoesNotThrow(() ->
                paymentService.authorizePayment(caseId + "_USD", "OPT-1", 50, "USD", "Pax", "k4"));
    }

    @Test
    @DisplayName("Payment 6.4: Dynamic tenant spending limit enforcement")
    void testTenantSpendingLimitEnforcement() {
        String caseId = "CASE-LIMIT-" + System.currentTimeMillis();
        TenantContext.setTenantId("tenant_restricted_agency");

        // Set agency limit to ₹25,000
        paymentService.setTenantSpendingLimit("tenant_restricted_agency", 25000);

        // Request ₹30,000 -> must fail closed
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                paymentService.authorizePayment(caseId, "OPT-1", 30000, "INR", "Pax", "k_limit_1"));
        assertTrue(ex.getMessage().contains("exceeds spending limit"));

        // Request ₹20,000 -> must succeed
        assertDoesNotThrow(() ->
                paymentService.authorizePayment(caseId, "OPT-1", 20000, "INR", "Pax", "k_limit_2"));
    }

    @Test
    @DisplayName("Payment 6.5: Financial reconciliation job detects over-capture discrepancies")
    void testFinancialReconciliationJob() {
        String caseId = "CASE-RECON-" + System.currentTimeMillis();

        // Issue card for ₹5,000
        PaymentTransaction authTxn = paymentService.authorizePayment(
                caseId, "OPT-1", 5000, "INR", "Recon Pax", "k_recon_auth"
        );

        // Create transaction capturing ₹7,500 (exceeding card limit of ₹5,000)
        PaymentTransaction overCap = new PaymentTransaction(
                "TXN-OVER-" + System.currentTimeMillis(),
                caseId,
                TenantContext.getTenantId(),
                authTxn.getVirtualCardId(),
                7500,
                "INR",
                "Airline GDS",
                PaymentTransaction.STATUS_CAPTURED,
                "GDS-OVER-123",
                java.time.LocalDateTime.now()
        );
        overCap.setReconciled(false);
        transactionRepository.saveAndFlush(overCap);

        // Run automated reconciliation job
        Map<String, Object> report = paymentService.runFinancialReconciliation();
        assertNotNull(report);
        assertTrue(((Number) report.get("totalProcessed")).intValue() >= 1);

        // Over-captured transaction must be flagged for manual review
        PaymentTransaction flagged = transactionRepository.findById(overCap.getId()).orElseThrow();
        assertEquals(PaymentTransaction.STATUS_MANUAL_REVIEW_REQUIRED, flagged.getStatus());
        assertTrue(flagged.getFailureReason().contains("Reconciliation Discrepancy"));
    }

    @Test
    @DisplayName("Payment 6.6: PCI DSS hygiene — zero CVV and masked PAN")
    void testPciDssHygiene() {
        String caseId = "CASE-PCI-" + System.currentTimeMillis();
        PaymentTransaction auth = paymentService.authorizePayment(
                caseId, "OPT-1", 2000, "INR", "Pci Pax", "k_pci"
        );

        VirtualCard card = cardRepository.findById(auth.getVirtualCardId()).orElseThrow();

        // Card number must be masked (e.g. •••• •••• •••• 1111)
        assertTrue(card.getMaskedCardNumber().contains("•") || card.getMaskedCardNumber().contains("*"), "PAN must be masked");
        assertEquals(4, card.getLastFour().length(), "Only last 4 digits stored");

        // Verify ledger entries contain zero CVV
        List<PaymentTransaction> ledger = paymentService.getLedgerForCase(caseId);
        for (PaymentTransaction tx : ledger) {
            if (tx.getAuditMetadata() != null) {
                assertFalse(tx.getAuditMetadata().toLowerCase().contains("cvv"), "CVV must NEVER be present in audit trails");
            }
        }
    }
}
