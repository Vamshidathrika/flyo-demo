package com.flyo.rescue.integration;

import com.flyo.rescue.integration.dto.MessageDeliveryRequest;
import com.flyo.rescue.integration.dto.MessageDeliveryResult;

public interface MessagingProvider {
    ProviderMetadata getMetadata();
    MessageDeliveryResult sendMessage(MessageDeliveryRequest request);
}
