package com.flyo.rescue.service.whatsapp;

import com.flyo.rescue.service.WhatsAppChatLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * In-memory Sandbox WhatsApp Adapter for offline development and integration testing.
 */
@Component
public class SandboxWhatsAppAdapter implements WhatsAppMessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxWhatsAppAdapter.class);
    private final WhatsAppChatLogService chatLogService;

    public SandboxWhatsAppAdapter(WhatsAppChatLogService chatLogService) {
        this.chatLogService = chatLogService;
    }

    @Override
    public String getProviderName() {
        return "Flyo In-Memory Sandbox WhatsApp Provider";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public boolean sendTextMessage(String toPhone, String text) {
        log.info("[Sandbox WhatsApp] Outbound to {}: {}", toPhone, text);
        chatLogService.logOutbound(toPhone, text, null);
        return true;
    }

    @Override
    public boolean sendInteractiveButtons(String toPhone, String bodyText, List<Map<String, String>> buttons) {
        log.info("[Sandbox WhatsApp] Outbound Interactive to {}: {}, Buttons: {}", toPhone, bodyText, buttons);
        chatLogService.logOutbound(toPhone, bodyText, buttons);
        return true;
    }
}
