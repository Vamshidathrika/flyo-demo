package com.flyo.rescue;

import com.flyo.rescue.config.WhatsAppConfig;
import com.flyo.rescue.service.MetaSignatureVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class WhatsAppWebhookTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WhatsAppConfig config;

    @Autowired
    private MetaSignatureVerifier signatureVerifier;

    @Test
    @DisplayName("Meta Webhook Verification: Correct challenge token returns 200 with raw challenge")
    void testWebhookChallengeVerificationSuccess() throws Exception {
        String challenge = "1158201444";
        mockMvc.perform(get("/api/whatsapp/webhook")
                        .param("hub.mode", "subscribe")
                        .param("hub.challenge", challenge)
                        .param("hub.verify_token", config.getVerifyToken()))
                .andExpect(status().isOk())
                .andExpect(content().string(challenge));
    }

    @Test
    @DisplayName("Meta Webhook Verification: Invalid token returns 403 Forbidden")
    void testWebhookChallengeVerificationInvalidToken() throws Exception {
        mockMvc.perform(get("/api/whatsapp/webhook")
                        .param("hub.mode", "subscribe")
                        .param("hub.challenge", "999999")
                        .param("hub.verify_token", "wrong_token_here"))
                .andExpect(status().isForbidden());
    }

    private String computeHmacSha256(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("sha256=");
        for (byte b : hmacBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("Meta Ingress: Valid JSON message payload with HMAC signature returns 200 EVENT_RECEIVED")
    void testWebhookMessageIngressSuccess() throws Exception {
        String payload = """
                {
                  "object": "whatsapp_business_account",
                  "entry": [
                    {
                      "id": "100234567890",
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "messages": [
                              {
                                "from": "919876543210",
                                "id": "wamid.TEST_ID_001",
                                "timestamp": "1730107200",
                                "type": "text",
                                "text": {
                                  "body": "HELP"
                                }
                              }
                            ]
                          },
                          "field": "messages"
                        }
                      ]
                    }
                  ]
                }
                """;

        String signature = computeHmacSha256(payload, config.getAppSecret());

        mockMvc.perform(post("/api/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signature)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("EVENT_RECEIVED"));
    }

    @Test
    @DisplayName("Meta Ingress: Invalid HMAC signature returns 403 Forbidden")
    void testWebhookMessageIngressInvalidSignature() throws Exception {
        String payload = "{\"test\": true}";

        mockMvc.perform(post("/api/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=invalid_tampered_signature_hex")
                        .content(payload))
                .andExpect(status().isForbidden());
    }
}
