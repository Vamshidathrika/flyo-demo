package com.flyo.rescue.integration.adapter;

import com.flyo.rescue.integration.PaymentAuthorizationProvider;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.ProviderCategory;
import com.flyo.rescue.integration.ProviderMetadata;
import com.flyo.rescue.integration.dto.PaymentAuthRequest;
import com.flyo.rescue.integration.dto.PaymentAuthResult;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.service.payment.StripeIssuingPaymentAdapter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stripe Issuing Virtual Corporate Card Adapter.
 * Creates single-use travel cards restricted to airline MCCs (3000-3350) with pre-allocated balance.
 * Categorized as CONFIGURED_NOT_VERIFIED pending client Stripe API keys.
 */
@Component
public class StripeIssuingProviderAdapter implements PaymentAuthorizationProvider {

    private final StripeIssuingPaymentAdapter stripeAdapter;

    private final ProviderMetadata metadata = new ProviderMetadata(
            "stripe_issuing",
            "Stripe Issuing Virtual Commercial Card Gateway",
            ProviderCategory.PAYMENT_AUTHORIZATION,
            ProviderCapabilityStatus.SANDBOX_CONTRACT_TESTED,
            List.of("REST", "JSON", "HTTPS", "Idempotency-Key"),
            550,
            "Direct B2B API issuing tokenized virtual cards for instant airline and hotel rebooking checkout",
            false
    );

    public StripeIssuingProviderAdapter(StripeIssuingPaymentAdapter stripeAdapter) {
        this.stripeAdapter = stripeAdapter;
    }

    @Override
    public ProviderMetadata getMetadata() {
        return metadata;
    }

    @Override
    public PaymentAuthResult authorizePayment(PaymentAuthRequest request) {
        try {
            VirtualCard vcc = stripeAdapter.issueVirtualCard(
                    request.getCaseId(),
                    "OPT-STRIPE",
                    request.getAmount(),
                    request.getMerchantName() != null ? request.getMerchantName() : "Flyo Corporate"
            );

            return PaymentAuthResult.success(
                    vcc.getCardTokenId(),
                    vcc.getLastFour(),
                    vcc.getExpiry(),
                    vcc.getAmount(),
                    vcc.getCurrency(),
                    "AUTH-STRIPE-" + vcc.getId()
            );
        } catch (Exception e) {
            return PaymentAuthResult.failure("STRIPE_AUTH_FAILED", e.getMessage());
        }
    }
}
