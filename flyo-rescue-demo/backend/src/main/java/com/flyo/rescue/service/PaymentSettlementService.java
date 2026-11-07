package com.flyo.rescue.service;

import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.repository.PaymentTransactionRepository;
import com.flyo.rescue.repository.VirtualCardRepository;
import com.flyo.rescue.security.TenantContext;
import com.flyo.rescue.service.payment.PaymentGatewayAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise Payment Settlement, Virtual Corporate Card (VCC) Lifecycle & Financial Reconciliation Ledger.
 *
 * Enforces:
 * - Clear separation of Authorization, Capture, Void, and Refund states
 * - Idempotent payment execution with unique idempotency keys
 * - Currency precision and ISO 4217 code validation
 * - Dynamic tenant-isolated spending limits
 * - Background automated financial reconciliation
 * - Zero storage of CVV, masked PAN logs (PCI DSS hygiene)
 */
@Service
public class PaymentSettlementService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettlementService.class);
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("INR", "USD", "EUR", "GBP");

    private final PaymentGatewayAdapter paymentAdapter;
    private final VirtualCardRepository cardRepository;
    private final PaymentTransactionRepository transactionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FlyoMetricsService metricsService;

    // Configurable per-tenant / corporate account spending limits
    private final Map<String, Integer> tenantSpendingLimits = new ConcurrentHashMap<>();

    public PaymentSettlementService(PaymentGatewayAdapter paymentAdapter,
                                    VirtualCardRepository cardRepository,
                                    PaymentTransactionRepository transactionRepository) {
        this.paymentAdapter = paymentAdapter;
        this.cardRepository = cardRepository;
        this.transactionRepository = transactionRepository;

        // Initialize standard corporate agency spending tiers
        tenantSpendingLimits.put("Flyo Corporate", 100000);
        tenantSpendingLimits.put("tenant_default", 50000);
        tenantSpendingLimits.put("tenant_alpha", 75000);
        tenantSpendingLimits.put("tenant_beta", 60000);
        tenantSpendingLimits.put("Default", 50000);
    }

    private void validateCurrencyAndAmount(int amount, String currency) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Payment amount must be strictly positive (> 0). Given: " + amount);
        }
        if (currency == null || !SUPPORTED_CURRENCIES.contains(currency.toUpperCase())) {
            throw new IllegalArgumentException("Unsupported currency: '" + currency + "'. Supported ISO 4217 currencies: " + SUPPORTED_CURRENCIES);
        }
    }

    // -------------------------------------------------------------------------
    // 1. AUTHORIZATION PHASE
    // -------------------------------------------------------------------------
    @Transactional
    public PaymentTransaction authorizePayment(String caseId, String optionId, int amount, String currency,
                                               String passengerName, String idempotencyKey) {
        validateCurrencyAndAmount(amount, currency);
        String currentTenant = TenantContext.getTenantId();

        // 1. Idempotency Check
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<PaymentTransaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("[Payment Ledger] Deduplicated authorization request: transaction already exists for idempotencyKey '{}'", idempotencyKey);
                return existing.get();
            }
        }

        // 2. Tenant Spending Limit Check
        if (!verifyTenantSpendingLimit(currentTenant, amount, currency)) {
            throw new IllegalArgumentException(String.format("Requested authorization amount %s %d exceeds spending limit for tenant '%s'",
                    currency, amount, currentTenant));
        }

        // 3. Issue Virtual Corporate Card
        VirtualCard card = paymentAdapter.issueVirtualCard(caseId, optionId, amount, passengerName);

        // 4. Record Authorization in Financial Ledger
        String txnId = "AUTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String refId = "AUTHREF-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        PaymentTransaction transaction = new PaymentTransaction(
                txnId,
                caseId,
                currentTenant,
                card.getId(),
                amount,
                currency.toUpperCase(),
                paymentAdapter.getProviderName(),
                PaymentTransaction.STATUS_AUTHORIZED,
                refId,
                LocalDateTime.now()
        );
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setTransactionType(PaymentTransaction.TYPE_AUTH);
        transaction.setReconciled(false);
        transaction.setAuditMetadata("VCC Issued: " + card.getMaskedCardNumber() + " with limit " + currency + " " + amount);

        return transactionRepository.save(transaction);
    }

    // -------------------------------------------------------------------------
    // 2. CAPTURE / SETTLEMENT PHASE
    // -------------------------------------------------------------------------
    @Transactional
    public PaymentTransaction capturePayment(String caseId, String virtualCardId, int amount, String currency,
                                             String referenceId, String idempotencyKey) {
        validateCurrencyAndAmount(amount, currency);
        String currentTenant = TenantContext.getTenantId();

        // Idempotency Check
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<PaymentTransaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("[Payment Ledger] Deduplicated capture request: transaction already exists for idempotencyKey '{}'", idempotencyKey);
                return existing.get();
            }
        }

        String txnId = "CAP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String effectiveRef = referenceId != null && !referenceId.isBlank()
                ? referenceId
                : "CAPREF-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        PaymentTransaction transaction = new PaymentTransaction(
                txnId,
                caseId,
                currentTenant,
                virtualCardId,
                amount,
                currency.toUpperCase(),
                paymentAdapter.getProviderName(),
                PaymentTransaction.STATUS_CAPTURED,
                effectiveRef,
                LocalDateTime.now()
        );
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setTransactionType(PaymentTransaction.TYPE_CAPTURE);
        transaction.setReconciled(false);

        PaymentTransaction saved = transactionRepository.save(transaction);

        if (virtualCardId != null) {
            cardRepository.findById(virtualCardId).ifPresent(c -> {
                c.setStatus("SETTLED");
                cardRepository.save(c);
            });
        }
        return saved;
    }

    // -------------------------------------------------------------------------
    // 3. VOID & REFUND PHASE
    // -------------------------------------------------------------------------
    @Transactional
    public PaymentTransaction voidAuthorization(String caseId, String virtualCardId, int amount, String reason) {
        String txnId = "VOID-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String refId = "REV-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        PaymentTransaction transaction = new PaymentTransaction(
                txnId,
                caseId,
                TenantContext.getTenantId(),
                virtualCardId,
                amount,
                "INR",
                paymentAdapter.getProviderName(),
                PaymentTransaction.STATUS_VOIDED,
                refId,
                LocalDateTime.now()
        );
        transaction.setTransactionType(PaymentTransaction.TYPE_VOID);
        transaction.setReconciled(true);
        transaction.setReconciledAt(LocalDateTime.now());
        transaction.setFailureReason(reason);

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
    public PaymentTransaction refundCapturedPayment(String caseId, String virtualCardId, int amount, String reason, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<PaymentTransaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        String txnId = "REFUND-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String refId = "RFD-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        PaymentTransaction transaction = new PaymentTransaction(
                txnId,
                caseId,
                TenantContext.getTenantId(),
                virtualCardId,
                amount,
                "INR",
                paymentAdapter.getProviderName(),
                PaymentTransaction.STATUS_REFUNDED,
                refId,
                LocalDateTime.now()
        );
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setTransactionType(PaymentTransaction.TYPE_REFUND);
        transaction.setReconciled(true);
        transaction.setReconciledAt(LocalDateTime.now());
        transaction.setFailureReason(reason);

        PaymentTransaction saved = transactionRepository.save(transaction);
        if (virtualCardId != null) {
            cardRepository.findById(virtualCardId).ifPresent(c -> {
                c.setStatus("REFUNDED");
                cardRepository.save(c);
            });
        }
        return saved;
    }

    @Transactional
    public PaymentTransaction refundCapturedPayment(String caseId, String virtualCardId, int amount, String reason) {
        return refundCapturedPayment(caseId, virtualCardId, amount, reason, null);
    }

    // -------------------------------------------------------------------------
    // 4. FINANCIAL RECONCILIATION JOB
    // -------------------------------------------------------------------------
    @Transactional
    public Map<String, Object> runFinancialReconciliation() {
        List<PaymentTransaction> unreconciled = transactionRepository.findByReconciledFalse();
        int reconciledCount = 0;
        int discrepancyCount = 0;

        for (PaymentTransaction tx : unreconciled) {
            if (tx.getVirtualCardId() != null) {
                Optional<VirtualCard> cardOpt = cardRepository.findById(tx.getVirtualCardId());
                if (cardOpt.isPresent()) {
                    VirtualCard card = cardOpt.get();
                    if (tx.getAmount() > card.getAmount()) {
                        // Discrepancy detected: transaction amount exceeds card limit
                        tx.setStatus(PaymentTransaction.STATUS_MANUAL_REVIEW_REQUIRED);
                        tx.setFailureReason("Reconciliation Discrepancy: captured amount (" + tx.getAmount() +
                                ") exceeds card limit (" + card.getAmount() + ")");
                        discrepancyCount++;
                        if (metricsService != null) {
                            metricsService.recordPaymentDiscrepancy(tx.getTenantId(), "CARD_LIMIT_EXCEEDED");
                        }
                    } else {
                        tx.setReconciled(true);
                        tx.setReconciledAt(LocalDateTime.now());
                        reconciledCount++;
                    }
                    transactionRepository.save(tx);
                }
            } else {
                tx.setReconciled(true);
                tx.setReconciledAt(LocalDateTime.now());
                transactionRepository.save(tx);
                reconciledCount++;
            }
        }

        log.info("[Payment Reconciliation] Completed. Reconciled: {}, Discrepancies flagged: {}", reconciledCount, discrepancyCount);
        return Map.of(
                "totalProcessed", unreconciled.size(),
                "reconciledCount", reconciledCount,
                "discrepancyCount", discrepancyCount,
                "timestamp", LocalDateTime.now().toString()
        );
    }

    // -------------------------------------------------------------------------
    // 5. LEGACY ADAPTER COMPATIBILITY & TENANT LIMITS
    // -------------------------------------------------------------------------
    @Transactional
    public VirtualCard issueVirtualCard(String caseId, String optionId, int amount, String passengerName) {
        String tenantId = TenantContext.getTenantId();
        if (!verifyTenantSpendingLimit(tenantId, amount, "INR")) {
            throw new IllegalArgumentException("Requested card amount ₹" + amount + " exceeds spending limit for tenant " + tenantId);
        }
        return paymentAdapter.issueVirtualCard(caseId, optionId, amount, passengerName);
    }

    @Transactional
    public PaymentTransaction recordSettlement(String caseId, String virtualCardId, int amount, String vendor) {
        return paymentAdapter.recordSettlement(caseId, virtualCardId, amount, vendor);
    }

    public Optional<VirtualCard> getActiveCardForCase(String caseId) {
        return cardRepository.findFirstByCaseIdOrderByIssuedAtDesc(caseId)
                .filter(c -> !"CANCELLED".equalsIgnoreCase(c.getStatus()) && !"VOIDED".equalsIgnoreCase(c.getStatus()));
    }

    @Transactional
    public boolean voidVirtualCard(String caseId, String virtualCardId) {
        log.info("[Payment Settlement] Voiding VCC {} for Case {}", virtualCardId, caseId);
        return paymentAdapter.voidAuthorization(caseId, virtualCardId);
    }

    public List<PaymentTransaction> getLedgerForCase(String caseId) {
        return transactionRepository.findByCaseIdOrderByTimestampAsc(caseId);
    }

    public String getActivePaymentProviderName() {
        return paymentAdapter.getProviderName();
    }

    public boolean verifyTenantSpendingLimit(String tenantId, int amount, String currency) {
        int limit = getTenantSpendingLimit(tenantId, currency);
        return amount <= limit;
    }

    public boolean verifyAgencySpendingLimit(String caseId, int amount) {
        return verifyTenantSpendingLimit(TenantContext.getTenantId(), amount, "INR");
    }

    public boolean verifyAgencySpendingLimit(String caseId, int amount, String currency, String agencyName) {
        String key = (agencyName != null && !agencyName.isBlank()) ? agencyName : TenantContext.getTenantId();
        return verifyTenantSpendingLimit(key, amount, currency);
    }

    public int getTenantSpendingLimit(String tenantId, String currency) {
        if (tenantId != null && tenantSpendingLimits.containsKey(tenantId)) {
            return tenantSpendingLimits.get(tenantId);
        }
        return tenantSpendingLimits.getOrDefault("Default", 50000);
    }

    public void setTenantSpendingLimit(String tenantId, int limit) {
        tenantSpendingLimits.put(tenantId, limit);
    }
}
