package com.flyo.rescue.service.payment;

import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.VirtualCard;

/**
 * Pluggable Payment & Treasury Gateway Adapter Interface.
 *
 * Provides virtual card issuance, credit settlement, and merchant restriction capabilities.
 */
public interface PaymentGatewayAdapter {

    String getProviderName();

    boolean isConfigured();

    VirtualCard issueVirtualCard(String caseId, String optionId, int amount, String passengerName);

    PaymentTransaction recordSettlement(String caseId, String virtualCardId, int amount, String vendor);

    boolean voidAuthorization(String caseId, String virtualCardId);
}
