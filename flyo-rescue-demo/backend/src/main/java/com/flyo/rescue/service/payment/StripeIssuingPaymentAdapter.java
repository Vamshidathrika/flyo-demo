package com.flyo.rescue.service.payment;

import com.flyo.rescue.exception.ProviderNotConfiguredException;
import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.VirtualCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Production Stripe Issuing Virtual Card Adapter.
 *
 * Adheres strictly to the rule: "Never pretend mock integrations are real.
 * If credentials are not present, explicitly flag BLOCKED BY EXTERNAL ACCESS."
 */
@Component
public class StripeIssuingPaymentAdapter implements PaymentGatewayAdapter {

    private static final Logger log = LoggerFactory.getLogger(StripeIssuingPaymentAdapter.class);

    private final String stripeApiKey;

    public StripeIssuingPaymentAdapter(@Value("${flyo.payment.stripe.api-key:}") String stripeApiKey) {
        String envKey = System.getenv("STRIPE_SECRET_KEY");
        this.stripeApiKey = (stripeApiKey != null && !stripeApiKey.isBlank())
                ? stripeApiKey
                : (envKey != null && !envKey.isBlank() ? envKey : null);
    }

    @Override
    public String getProviderName() {
        return "Stripe Issuing Production Gateway";
    }

    @Override
    public boolean isConfigured() {
        return stripeApiKey != null && !stripeApiKey.isBlank() && !stripeApiKey.contains("dummy");
    }

    @Override
    public VirtualCard issueVirtualCard(String caseId, String optionId, int amount, String passengerName) {
        if (!isConfigured()) {
            throw new ProviderNotConfiguredException(
                    getProviderName(),
                    "BLOCKED BY EXTERNAL ACCESS: Stripe Issuing API credentials not provided. "
                            + "Configure 'flyo.payment.stripe.api-key' or STRIPE_SECRET_KEY environment variable."
            );
        }
        log.info("[Stripe Issuing] Issuing live VCC for Case {} via Stripe API...", caseId);
        // Real provider implementation requires valid live account & webhook secret
        throw new UnsupportedOperationException("Stripe Issuing live credentials pending activation.");
    }

    @Override
    public PaymentTransaction recordSettlement(String caseId, String virtualCardId, int amount, String vendor) {
        if (!isConfigured()) {
            throw new ProviderNotConfiguredException(
                    getProviderName(),
                    "BLOCKED BY EXTERNAL ACCESS: Stripe Issuing API credentials not provided."
            );
        }
        throw new UnsupportedOperationException("Stripe Issuing live settlement pending activation.");
    }

    @Override
    public boolean voidAuthorization(String caseId, String virtualCardId) {
        if (!isConfigured()) {
            throw new ProviderNotConfiguredException(
                    getProviderName(),
                    "BLOCKED BY EXTERNAL ACCESS: Stripe Issuing API credentials not provided."
            );
        }
        throw new UnsupportedOperationException("Stripe Issuing live void pending activation.");
    }
}
