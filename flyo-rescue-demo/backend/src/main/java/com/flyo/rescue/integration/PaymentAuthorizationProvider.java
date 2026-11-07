package com.flyo.rescue.integration;

import com.flyo.rescue.integration.dto.PaymentAuthRequest;
import com.flyo.rescue.integration.dto.PaymentAuthResult;

public interface PaymentAuthorizationProvider {
    ProviderMetadata getMetadata();
    PaymentAuthResult authorizePayment(PaymentAuthRequest request);
}
