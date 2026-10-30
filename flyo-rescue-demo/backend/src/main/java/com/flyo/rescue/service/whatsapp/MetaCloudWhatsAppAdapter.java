package com.flyo.rescue.service.whatsapp;

import com.flyo.rescue.service.MetaWhatsAppClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Meta Cloud WhatsApp API Adapter.
 *
 * Dispatches live outbound WhatsApp messages via Meta Graph API v21.0
 * or logs locally in sandbox mode when live credentials are unconfigured.
 */
@Component
@Primary
public class MetaCloudWhatsAppAdapter implements WhatsAppMessagingProvider {

    private final MetaWhatsAppClient metaWhatsAppClient;

    public MetaCloudWhatsAppAdapter(MetaWhatsAppClient metaWhatsAppClient) {
        this.metaWhatsAppClient = metaWhatsAppClient;
    }

    @Override
    public String getProviderName() {
        return "Meta WhatsApp Cloud API (Graph v21.0)";
    }

    @Override
    public boolean isConfigured() {
        return metaWhatsAppClient.isConfigured();
    }

    @Override
    public boolean sendTextMessage(String toPhone, String text) {
        return metaWhatsAppClient.sendTextMessage(toPhone, text);
    }

    @Override
    public boolean sendInteractiveButtons(String toPhone, String bodyText, List<Map<String, String>> buttons) {
        return metaWhatsAppClient.sendInteractiveButtons(toPhone, bodyText, buttons);
    }
}
