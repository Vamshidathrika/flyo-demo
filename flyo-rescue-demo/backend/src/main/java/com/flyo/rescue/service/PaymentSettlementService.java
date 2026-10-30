package com.flyo.rescue.service;

import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.repository.PaymentTransactionRepository;
import com.flyo.rescue.repository.VirtualCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentSettlementService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettlementService.class);

    private final VirtualCardRepository cardRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final AuditService auditService;

    public PaymentSettlementService(VirtualCardRepository cardRepository,
                                  PaymentTransactionRepository transactionRepository,
                                  AuditService auditService) {
        this.cardRepository = cardRepository;
        this.transactionRepository = transactionRepository;
        this.auditService = auditService;
    }

    /**
     * Issues a single-use Corporate Virtual Credit Card (VCC) locked to airline MCC (3000-3350)
     * with an exact spending limit for the approved recovery option.
     */
    @Transactional
    public VirtualCard issueVirtualCard(String caseId, String optionId, int amount, String passengerName) {
        // Reuse active card if already issued for this case and option
        Optional<VirtualCard> existing = cardRepository.findFirstByCaseIdOrderByIssuedAtDesc(caseId);
        if (existing.isPresent() && "ISSUED".equals(existing.get().getStatus()) && existing.get().getOptionId().equals(optionId)) {
            return existing.get();
        }

        String cardId = "VCC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        
        // Generate calibrated corporate test card: 4532 98xx xxxx 8821
        int randMid = 1000 + (int)(Math.random() * 8999);
        int randLast = 1000 + (int)(Math.random() * 8999);
        String cardNumber = String.format("453298%04d%04d", randMid, randLast);
        String expiry = "12/28";
        String cvv = String.format("%03d", 100 + (int)(Math.random() * 899));
        String cardHolder = passengerName != null && !passengerName.isBlank()
                ? "FLYO CORP / " + passengerName.toUpperCase()
                : "FLYO CORPORATE TRAVEL DESK";

        VirtualCard card = new VirtualCard(
                cardId,
                caseId,
                optionId,
                cardNumber,
                cardHolder,
                expiry,
                cvv,
                amount,
                "INR",
                "ISSUED",
                "3000 - Airlines & Air Carriers",
                LocalDateTime.now()
        );

        VirtualCard saved = cardRepository.save(card);

        log.info("[Payment Settlement] Issued VCC {} for Case {} (Option {}), Amount: ₹{}",
                cardId, caseId, optionId, amount);

        auditService.logEvent(
                caseId,
                "VCC_ISSUED",
                "Flyo Treasury Gateway",
                String.format("Single-use Corporate Virtual Card %s issued for ₹%,d (Card: %s, Exp: %s). Locked to Airline MCC.",
                        cardId, amount, saved.getMaskedCardNumber(), expiry)
        );

        return saved;
    }

    /**
     * Records final settlement against the issued Virtual Credit Card upon successful airline ticket reissue.
     */
    @Transactional
    public PaymentTransaction recordSettlement(String caseId, String virtualCardId, int amount, String vendor) {
        String txnId = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String refId = "REF-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        PaymentTransaction transaction = new PaymentTransaction(
                txnId,
                caseId,
                virtualCardId,
                amount,
                "INR",
                vendor != null && !vendor.isBlank() ? vendor : "Partner Airline Portal",
                "SETTLED",
                refId,
                LocalDateTime.now()
        );

        PaymentTransaction saved = transactionRepository.save(transaction);

        // Update Card Status to CHARGED
        if (virtualCardId != null) {
            cardRepository.findById(virtualCardId).ifPresent(c -> {
                c.setStatus("CHARGED");
                cardRepository.save(c);
            });
        }

        log.info("[Payment Settlement] Settlement recorded {} for Case {}, Amount: ₹{}",
                txnId, caseId, amount);

        auditService.logEvent(
                caseId,
                "PAYMENT_SETTLED",
                "RazorpayX / Flyo Settlement",
                String.format("Fare difference of ₹%,d successfully settled on VCC %s with %s. Transaction Ref: %s.",
                        amount, virtualCardId, transaction.getVendor(), refId)
        );

        return saved;
    }

    public Optional<VirtualCard> getActiveCardForCase(String caseId) {
        return cardRepository.findFirstByCaseIdOrderByIssuedAtDesc(caseId);
    }

    public List<PaymentTransaction> getLedgerForCase(String caseId) {
        return transactionRepository.findByCaseIdOrderByTimestampAsc(caseId);
    }
}
