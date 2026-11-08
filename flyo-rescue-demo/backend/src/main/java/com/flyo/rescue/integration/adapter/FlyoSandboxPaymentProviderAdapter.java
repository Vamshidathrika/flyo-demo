package com.flyo.rescue.integration.adapter;

import com.flyo.rescue.integration.PaymentAuthorizationProvider;
import com.flyo.rescue.integration.PaymentSettlementProvider;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.ProviderCategory;
import com.flyo.rescue.integration.ProviderMetadata;
import com.flyo.rescue.integration.dto.*;
import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.service.PaymentSettlementService;
import com.flyo.rescue.service.payment.SandboxPaymentAdapter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!prod")
public class FlyoSandboxPaymentProviderAdapter implements PaymentAuthorizationProvider, PaymentSettlementProvider {

    private final SandboxPaymentAdapter sandboxPaymentAdapter;
    private final PaymentSettlementService paymentSettlementService;

    private final ProviderMetadata authMetadata = new ProviderMetadata(
            "flyo_sandbox_payment_auth",
            "Flyo Sandbox Treasury Payment Authorizer",
            ProviderCategory.PAYMENT_AUTHORIZATION,
            ProviderCapabilityStatus.SANDBOX_CONTRACT_TESTED,
            List.of("Internal", "REST", "Tokenized"),
            15,
            "PCI-compliant tokenized virtual card issuing adapter for local testing and demonstration",
            false
    );

    private final ProviderMetadata settlementMetadata = new ProviderMetadata(
            "flyo_sandbox_payment_settle",
            "Flyo Sandbox Treasury Payment Settler",
            ProviderCategory.PAYMENT_SETTLEMENT,
            ProviderCapabilityStatus.SANDBOX_CONTRACT_TESTED,
            List.of("Internal", "JPA Ledger"),
            20,
            "Captures settlement transactions against tokenized virtual cards in local ledger",
            false
    );

    public FlyoSandboxPaymentProviderAdapter(SandboxPaymentAdapter sandboxPaymentAdapter,
                                             PaymentSettlementService paymentSettlementService) {
        this.sandboxPaymentAdapter = sandboxPaymentAdapter;
        this.paymentSettlementService = paymentSettlementService;
    }

    @Override
    public ProviderMetadata getMetadata() {
        return authMetadata;
    }

    @Override
    public PaymentAuthResult authorizePayment(PaymentAuthRequest request) {
        VirtualCard vcc = sandboxPaymentAdapter.issueVirtualCard(
                request.getCaseId(),
                "OPT-DEFAULT",
                request.getAmount(),
                request.getMerchantName() != null ? request.getMerchantName() : "Flyo Corporate"
        );

        return PaymentAuthResult.success(
                vcc.getCardTokenId(),
                vcc.getLastFour(),
                vcc.getExpiry(),
                vcc.getAmount(),
                vcc.getCurrency(),
                "AUTH-SANDBOX-" + vcc.getId()
        );
    }

    @Override
    public PaymentSettlementResult settlePayment(PaymentSettlementRequest request) {
        PaymentTransaction settlement = paymentSettlementService.recordSettlement(
                request.getCaseId(),
                request.getPaymentToken(),
                request.getAmount(),
                request.getMerchantName() != null ? request.getMerchantName() : "StarSky Airlines"
        );

        return PaymentSettlementResult.success(
                settlement.getId(),
                settlement.getAmount(),
                settlement.getCurrency()
        );
    }
}
