package com.flyo.rescue.integration.adapter;

import com.flyo.rescue.integration.MessagingProvider;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.ProviderCategory;
import com.flyo.rescue.integration.ProviderMetadata;
import com.flyo.rescue.integration.dto.MessageDeliveryRequest;
import com.flyo.rescue.integration.dto.MessageDeliveryResult;
import com.flyo.rescue.model.SimulatedMessage;
import com.flyo.rescue.service.MessageService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SimulatedWhatsAppAdapter implements MessagingProvider {

    private final MessageService messageService;

    private final ProviderMetadata metadata = new ProviderMetadata(
            "simulated_whatsapp",
            "Flyo In-Memory Simulated WhatsApp Gateway",
            ProviderCategory.MESSAGING,
            ProviderCapabilityStatus.SANDBOX_CONNECTED,
            List.of("Internal", "REST Console", "JPA"),
            5,
            "Captures outbound passenger notifications and enables simulated passenger responses for testing",
            false
    );

    public SimulatedWhatsAppAdapter(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public ProviderMetadata getMetadata() {
        return metadata;
    }

    @Override
    public MessageDeliveryResult sendMessage(MessageDeliveryRequest request) {
        SimulatedMessage msg = messageService.sendCustomMessage(
                request.getCaseId(),
                "SENT_SIMULATED",
                request.getRecipient(),
                request.getMessageBody()
        );

        return MessageDeliveryResult.success(String.valueOf(msg.getId()), "SIMULATED_WHATSAPP");
    }
}
