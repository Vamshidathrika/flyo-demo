package com.flyo.rescue.service.whatsapp;

import java.util.List;
import java.util.Map;

/**
 * Universal WhatsApp Messaging Provider Interface.
 *
 * Implements Meta Cloud API, sandbox simulated messaging, and webhook callbacks.
 */
public interface WhatsAppMessagingProvider {

    String getProviderName();

    boolean isConfigured();

    boolean sendTextMessage(String toPhone, String text);

    boolean sendInteractiveButtons(String toPhone, String bodyText, List<Map<String, String>> buttons);
}
