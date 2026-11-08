package com.flyo.rescue.integration;

import com.flyo.rescue.integration.dto.PaymentSettlementRequest;
import com.flyo.rescue.integration.dto.PaymentSettlementResult;

public interface PaymentSettlementProvider {
    ProviderMetadata getMetadata();
    PaymentSettlementResult settlePayment(PaymentSettlementRequest request);
}
