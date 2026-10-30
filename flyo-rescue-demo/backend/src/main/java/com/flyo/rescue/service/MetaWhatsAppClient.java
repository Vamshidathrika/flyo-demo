package com.flyo.rescue.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.config.WhatsAppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class MetaWhatsAppClient {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppClient.class);
    private static final String META_GRAPH_URL = "https://graph.facebook.com/v21.0";

    private final WhatsAppConfig config;
    private final WhatsAppChatLogService chatLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public MetaWhatsAppClient(WhatsAppConfig config, WhatsAppChatLogService chatLogService) {
        this.config = config;
        this.chatLogService = chatLogService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean sendTextMessage(String toPhone, String text) {
        String cleanPhone = cleanPhoneNumber(toPhone);
        log.info("[Meta WhatsApp Outbound] To: {}, Text: {}", cleanPhone, text);

        // Record in operator chat history for live UI visibility
        chatLogService.logOutbound(cleanPhone, text, null);

        if (!isConfigured()) {
            log.info("[Meta WhatsApp Outbound] Mock mode active: message logged without remote HTTP dispatch.");
            return true;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messaging_product", "whatsapp");
            payload.put("recipient_type", "individual");
            payload.put("to", cleanPhone);
            payload.put("type", "text");
            payload.put("text", Map.of("preview_url", false, "body", text));

            return dispatchToMeta(payload);
        } catch (Exception e) {
            log.error("[Meta WhatsApp Outbound] Error sending text message: {}", e.getMessage(), e);
            return false;
        }
    }

    public boolean sendInteractiveButtons(String toPhone, String bodyText, List<Map<String, String>> buttons) {
        String cleanPhone = cleanPhoneNumber(toPhone);
        log.info("[Meta WhatsApp Outbound Interactive] To: {}, Body: {}, Buttons: {}", cleanPhone, bodyText, buttons);

        // Record formatted interactive message in operator chat history
        chatLogService.logOutbound(cleanPhone, bodyText, buttons);

        if (!isConfigured()) {
            log.info("[Meta WhatsApp Outbound] Mock mode active: interactive message logged locally.");
            return true;
        }

        try {
            List<Map<String, Object>> buttonList = new ArrayList<>();
            for (Map<String, String> btn : buttons) {
                buttonList.add(Map.of(
                        "type", "reply",
                        "reply", Map.of(
                                "id", btn.get("id"),
                                "title", btn.get("title")
                        )
                ));
            }

            Map<String, Object> interactive = new LinkedHashMap<>();
            interactive.put("type", "button");
            interactive.put("body", Map.of("text", bodyText));
            interactive.put("action", Map.of("buttons", buttonList));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messaging_product", "whatsapp");
            payload.put("recipient_type", "individual");
            payload.put("to", cleanPhone);
            payload.put("type", "interactive");
            payload.put("interactive", interactive);

            return dispatchToMeta(payload);
        } catch (Exception e) {
            log.error("[Meta WhatsApp Outbound] Error sending interactive message: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean dispatchToMeta(Map<String, Object> payload) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(payload);
        String url = String.format("%s/%s/messages", META_GRAPH_URL, config.getPhoneNumberId());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + config.getAccessToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("[Meta WhatsApp Outbound] Successfully dispatched message. Response: {}", response.body());
            return true;
        } else {
            log.warn("[Meta WhatsApp Outbound] Meta API returned non-200 status {}: {}", response.statusCode(), response.body());
            return false;
        }
    }

    public boolean isConfigured() {
        return config.isEnabled()
                && config.getAccessToken() != null
                && !config.getAccessToken().isBlank()
                && !config.getAccessToken().startsWith("mock_")
                && config.getPhoneNumberId() != null
                && !config.getPhoneNumberId().isBlank()
                && !config.getPhoneNumberId().startsWith("mock_");
    }

    private String cleanPhoneNumber(String phone) {
        if (phone == null) return "919876543210";
        return phone.replaceAll("[^0-9]", "");
    }
}
