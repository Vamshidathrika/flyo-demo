package com.flyo.rescue;

import com.flyo.rescue.exception.ProviderNotConfiguredException;
import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.service.payment.SandboxPaymentAdapter;
import com.flyo.rescue.service.payment.StripeIssuingPaymentAdapter;
import com.flyo.rescue.service.rebook.AmadeusNdcBookingAdapter;
import com.flyo.rescue.service.rebook.PartnerPortalPlaywrightAdapter;
import com.flyo.rescue.service.rebook.SabreGdsBookingAdapter;
import com.flyo.rescue.service.whatsapp.MetaCloudWhatsAppAdapter;
import com.flyo.rescue.service.whatsapp.SandboxWhatsAppAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ProviderAdaptersIntegrationTest {

    @Autowired
    private SandboxPaymentAdapter sandboxPaymentAdapter;

    @Autowired
    private StripeIssuingPaymentAdapter stripePaymentAdapter;

    @Autowired
    private PartnerPortalPlaywrightAdapter playwrightBookingAdapter;

    @Autowired
    private AmadeusNdcBookingAdapter amadeusBookingAdapter;

    @Autowired
    private SabreGdsBookingAdapter sabreBookingAdapter;

    @Autowired
    private MetaCloudWhatsAppAdapter metaWhatsAppAdapter;

    @Autowired
    private SandboxWhatsAppAdapter sandboxWhatsAppAdapter;

    @Test
    @DisplayName("Phase 5: Payment Gateway Sandbox issues masked VCC and records settled ledger transaction")
    void testSandboxPaymentAdapter() {
        assertTrue(sandboxPaymentAdapter.isConfigured());
        assertNotNull(sandboxPaymentAdapter.getProviderName());

        VirtualCard card = sandboxPaymentAdapter.issueVirtualCard("CASE-ADAPTER-1", "O2", 4200, "Ananya Sharma");
        assertNotNull(card);
        assertEquals("CASE-ADAPTER-1", card.getCaseId());
        assertEquals("O2", card.getOptionId());
        assertEquals(4200, card.getAmount());
        assertEquals("ISSUED", card.getStatus());
        assertTrue(card.getMaskedCardNumber().startsWith("•••• •••• •••• "));
        assertEquals("12/28", card.getExpiry());
        assertNotNull(card.getCvv());

        PaymentTransaction txn = sandboxPaymentAdapter.recordSettlement("CASE-ADAPTER-1", card.getId(), 4200, "StarSky Airlines");
        assertNotNull(txn);
        assertEquals("SETTLED", txn.getStatus());
        assertEquals(4200, txn.getAmount());
        assertEquals("INR", txn.getCurrency());
    }

    @Test
    @DisplayName("Phase 5: Stripe Issuing without credentials explicitly throws BLOCKED BY EXTERNAL ACCESS")
    void testStripeIssuingBlockedWithoutCredentials() {
        assertFalse(stripePaymentAdapter.isConfigured(), "Stripe should not be configured in demo test environment");
        ProviderNotConfiguredException ex = assertThrows(ProviderNotConfiguredException.class, () -> {
            stripePaymentAdapter.issueVirtualCard("CASE-STRIPE", "O2", 4200, "Test Pax");
        });
        assertTrue(ex.getMessage().contains("BLOCKED BY EXTERNAL ACCESS"));
        assertTrue(ex.getMessage().contains("Stripe Issuing"));
    }

    @Test
    @DisplayName("Phase 6: Airline Booking Adapters - GDS & NDC blocked without credentials; Playwright active")
    void testFlightBookingAdapters() {
        assertTrue(playwrightBookingAdapter.isConfigured());
        assertEquals("Flyo Playwright Portal Automation Engine", playwrightBookingAdapter.getProviderName());

        assertFalse(amadeusBookingAdapter.isConfigured());
        ProviderNotConfiguredException amadeusEx = assertThrows(ProviderNotConfiguredException.class, () -> {
            amadeusBookingAdapter.executeRebook("C1", "O2", "FL301", "Ananya Sharma", null, null, null, 0);
        });
        assertTrue(amadeusEx.getMessage().contains("BLOCKED BY EXTERNAL ACCESS"));
        assertTrue(amadeusEx.getMessage().contains("Amadeus NDC"));

        assertFalse(sabreBookingAdapter.isConfigured());
        ProviderNotConfiguredException sabreEx = assertThrows(ProviderNotConfiguredException.class, () -> {
            sabreBookingAdapter.executeRebook("C1", "O2", "FL301", "Ananya Sharma", null, null, null, 0);
        });
        assertTrue(sabreEx.getMessage().contains("BLOCKED BY EXTERNAL ACCESS"));
        assertTrue(sabreEx.getMessage().contains("Sabre GDS"));
    }

    @Test
    @DisplayName("Phase 7: WhatsApp Messaging Providers handle text and interactive buttons")
    void testWhatsAppMessagingProviders() {
        assertNotNull(metaWhatsAppAdapter.getProviderName());
        boolean textSent = sandboxWhatsAppAdapter.sendTextMessage("919876543210", "Test Outbound WhatsApp Message");
        assertTrue(textSent);

        boolean interactiveSent = sandboxWhatsAppAdapter.sendInteractiveButtons(
                "919876543210",
                "Choose recovery action:",
                List.of(Map.of("id", "APPROVE_O2", "title", "Approve O2"), Map.of("id", "REJECT", "title", "Reject"))
        );
        assertTrue(interactiveSent);
    }
}
