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

    public PaymentSettlementService(PaymentGatewayAdapter paymentAdapter,
                                   VirtualCardRepository cardRepository,
                                   PaymentTransactionRepository transactionRepository) {
        this.paymentAdapter = paymentAdapter;
        this.cardRepository = cardRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public VirtualCard issueVirtualCard(String caseId, String optionId, int amount, String passengerName) {
        return paymentAdapter.issueVirtualCard(caseId, optionId, amount, passengerName);
    }

    @Transactional
    public PaymentTransaction recordSettlement(String caseId, String virtualCardId, int amount, String vendor) {
        return paymentAdapter.recordSettlement(caseId, virtualCardId, amount, vendor);
    }

    public Optional<VirtualCard> getActiveCardForCase(String caseId) {
        return cardRepository.findFirstByCaseIdOrderByIssuedAtDesc(caseId);
    }

    public List<PaymentTransaction> getLedgerForCase(String caseId) {
        return transactionRepository.findByCaseIdOrderByTimestampAsc(caseId);
    }

    public String getActivePaymentProviderName() {
        return paymentAdapter.getProviderName();
    }
}
