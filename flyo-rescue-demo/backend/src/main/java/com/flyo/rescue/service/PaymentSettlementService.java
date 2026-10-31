package com.flyo.rescue.service;

import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.repository.PaymentTransactionRepository;
import com.flyo.rescue.repository.VirtualCardRepository;
import com.flyo.rescue.service.payment.PaymentGatewayAdapter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class PaymentSettlementService {

    private final PaymentGatewayAdapter paymentAdapter;
    private final VirtualCardRepository cardRepository;
    private final PaymentTransactionRepository transactionRepository;

    // Configurable per-agency / corporate account spending limits
    private final java.util.Map<String, Integer> agencySpendingLimits = new java.util.concurrent.ConcurrentHashMap<>();

    public PaymentSettlementService(PaymentGatewayAdapter paymentAdapter,
                                   VirtualCardRepository cardRepository,
                                   PaymentTransactionRepository transactionRepository) {
        this.paymentAdapter = paymentAdapter;
        this.cardRepository = cardRepository;
        this.transactionRepository = transactionRepository;

        // Initialize standard corporate agency spending tiers
        agencySpendingLimits.put("Flyo Corporate", 100000);
        agencySpendingLimits.put("Sri Sai Travels", 50000);
        agencySpendingLimits.put("Default", 50000);
    }

    @Transactional
    public VirtualCard issueVirtualCard(String caseId, String optionId, int amount, String passengerName) {
        return paymentAdapter.issueVirtualCard(caseId, optionId, amount, passengerName);
    }

    @Transactional
    public PaymentTransaction recordSettlement(String caseId, String virtualCardId, int amount, String vendor) {
        return paymentAdapter.recordSettlement(caseId, virtualCardId, amount, vendor);
    }

    @Transactional
    public PaymentTransaction voidAuthorization(String caseId, String virtualCardId, int amount, String reason) {
        String txnId = "VOID-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String refId = "REV-" + java.util.UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        PaymentTransaction transaction = new PaymentTransaction(
                txnId,
                caseId,
                virtualCardId,
                amount,
                "INR",
                "Flyo Treasury Gateway",
                "VOIDED",
                refId,
                java.time.LocalDateTime.now()
        );

        PaymentTransaction saved = transactionRepository.save(transaction);
        if (virtualCardId != null) {
            cardRepository.findById(virtualCardId).ifPresent(c -> {
                c.setStatus("CANCELLED");
                cardRepository.save(c);
            });
        }
        return saved;
    }

    @Transactional
    public PaymentTransaction refundCapturedPayment(String caseId, String virtualCardId, int amount, String reason) {
        String txnId = "REFUND-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String refId = "RFD-" + java.util.UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        PaymentTransaction transaction = new PaymentTransaction(
                txnId,
                caseId,
                virtualCardId,
                amount,
                "INR",
                "Flyo Treasury Gateway",
                "REFUNDED",
                refId,
                java.time.LocalDateTime.now()
        );

        PaymentTransaction saved = transactionRepository.save(transaction);
        if (virtualCardId != null) {
            cardRepository.findById(virtualCardId).ifPresent(c -> {
                c.setStatus("REFUNDED");
                cardRepository.save(c);
            });
        }
        return saved;
    }

    public Optional<VirtualCard> getActiveCardForCase(String caseId) {
        return cardRepository.findFirstByCaseIdOrderByIssuedAtDesc(caseId)
                .filter(c -> !"CANCELLED".equalsIgnoreCase(c.getStatus()) && !"VOIDED".equalsIgnoreCase(c.getStatus()));
    }

    public List<PaymentTransaction> getLedgerForCase(String caseId) {
        return transactionRepository.findByCaseIdOrderByTimestampAsc(caseId);
    }

    public String getActivePaymentProviderName() {
        return paymentAdapter.getProviderName();
    }

    /**
     * Verifies that the proposed rebooking fare difference complies with corporate travel policy
     * and agency authorized spending limits.
     */
    public boolean verifyAgencySpendingLimit(String caseId, int amount) {
        return verifyAgencySpendingLimit(caseId, amount, "INR", "Default");
    }

    public boolean verifyAgencySpendingLimit(String caseId, int amount, String currency, String agencyName) {
        int limit = getAgencySpendingLimit(agencyName, currency);
        return amount <= limit;
    }

    public int getAgencySpendingLimit(String agencyName, String currency) {
        if (agencyName != null && agencySpendingLimits.containsKey(agencyName)) {
            return agencySpendingLimits.get(agencyName);
        }
        return agencySpendingLimits.getOrDefault("Default", 50000);
    }

    public void setAgencySpendingLimit(String agencyName, int limit) {
        agencySpendingLimits.put(agencyName, limit);
    }
}
