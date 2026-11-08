package com.flyo.rescue;

import com.flyo.rescue.config.ProductionSecurityValidator;
import com.flyo.rescue.exception.ProviderNotConfiguredException;
import com.flyo.rescue.integration.amadeus.AmadeusGdsSandboxClient;
import com.flyo.rescue.service.MetaSignatureVerifier;
import com.flyo.rescue.service.payment.StripeIssuingPaymentAdapter;
import com.flyo.rescue.service.rebook.AmadeusNdcBookingAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STAGE 9B ADVERSARIAL PRODUCTION CONFIGURATION TEST SUITE:
 * Validates that invalid production configurations (MOCK modes, sandbox payments,
 * insecure secrets, missing credentials, unverified webhooks) are explicitly rejected
 * and never silently fall back to mock behavior.
 */
public class Stage9BAdversarialProductionConfigTest {

    @Test
    @DisplayName("Stage 9B Adversarial: Production rejects MOCK airline booking provider")
    void testRejectsMockAirlineProvider() {
        MockEnvironment env = createBaseProdEnvironment();
        env.setProperty("airline.booking.provider", "MOCK_AIRLINE");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.onApplicationEvent(null));
        assertTrue(ex.getMessage().contains("strictly forbidden in production"));
    }

    @Test
    @DisplayName("Stage 9B Adversarial: Production rejects SANDBOX airline booking provider")
    void testRejectsSandboxAirlineProvider() {
        MockEnvironment env = createBaseProdEnvironment();
        env.setProperty("airline.booking.provider", "SANDBOX");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.onApplicationEvent(null));
        assertTrue(ex.getMessage().contains("strictly forbidden in production"));
    }

    @Test
    @DisplayName("Stage 9B Adversarial: Production rejects MOCK payment provider")
    void testRejectsMockPaymentProvider() {
        MockEnvironment env = createBaseProdEnvironment();
        env.setProperty("payment.vcc.provider", "MOCK_PAYMENT");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.onApplicationEvent(null));
        assertTrue(ex.getMessage().contains("strictly forbidden in production"));
    }

    @Test
    @DisplayName("Stage 9B Adversarial: Production rejects SANDBOX payment provider")
    void testRejectsSandboxPaymentProvider() {
        MockEnvironment env = createBaseProdEnvironment();
        env.setProperty("payment.vcc.provider", "SANDBOX");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.onApplicationEvent(null));
        assertTrue(ex.getMessage().contains("strictly forbidden in production"));
    }

    @Test
    @DisplayName("Stage 9B Adversarial: Production rejects Radar mockMode = true")
    void testRejectsRadarMockMode() {
        MockEnvironment env = createBaseProdEnvironment();
        env.setProperty("aviation.radar.mock-mode", "true");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.onApplicationEvent(null));
        assertTrue(ex.getMessage().contains("Aviation radar mockMode is strictly forbidden"));
    }

    @Test
    @DisplayName("Stage 9B Adversarial: Production rejects synthetic database seeding enabled")
    void testRejectsDatabaseSeedingInProduction() {
        MockEnvironment env = createBaseProdEnvironment();
        env.setProperty("flyo.seeding.enabled", "true");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.onApplicationEvent(null));
        assertTrue(ex.getMessage().contains("Synthetic database seeding"));
    }

    @Test
    @DisplayName("Stage 9B Adversarial: Production rejects insecure or default database password")
    void testRejectsDefaultDatabasePassword() {
        MockEnvironment env = createBaseProdEnvironment();
        env.setProperty("spring.datasource.password", "flyo_secure_password_2026");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.onApplicationEvent(null));
        assertTrue(ex.getMessage().contains("Default fallback database password detected"));
    }

    @Test
    @DisplayName("Stage 9B Adversarial: Production rejects non-PostgreSQL datasource URL")
    void testRejectsNonPostgreSqlUrl() {
        MockEnvironment env = createBaseProdEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:h2:mem:testdb");

        ProductionSecurityValidator validator = new ProductionSecurityValidator(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.onApplicationEvent(null));
        assertTrue(ex.getMessage().contains("Production profile requires PostgreSQL"));
    }

    @Test
    @DisplayName("Stage 9B Adversarial: Unconfigured Stripe provider fails closed with ProviderNotConfiguredException")
    void testStripeFailsClosedWithoutCredentials() {
        StripeIssuingPaymentAdapter adapter = new StripeIssuingPaymentAdapter("");
        assertFalse(adapter.isConfigured());

        ProviderNotConfiguredException ex = assertThrows(ProviderNotConfiguredException.class, () ->
                adapter.issueVirtualCard("C1", "O1", 5000, "Test Pax"));
        assertTrue(ex.getMessage().contains("BLOCKED BY EXTERNAL ACCESS"));
    }

    @Test
    @DisplayName("Stage 9B Adversarial: Unconfigured Amadeus NDC adapter fails closed with ProviderNotConfiguredException")
    void testAmadeusNdcFailsClosedWithoutCredentials() {
        AmadeusGdsSandboxClient unconfiguredGdsClient = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "", "", 5000, 5000);
        AmadeusNdcBookingAdapter ndcAdapter = new AmadeusNdcBookingAdapter(unconfiguredGdsClient);

        assertFalse(ndcAdapter.isConfigured());
        ProviderNotConfiguredException ex = assertThrows(ProviderNotConfiguredException.class, () ->
                ndcAdapter.executeRebook("CASE-999", "OPT-1", "AI-839", "Test Pax", "4111111111111111", "12/28", "123", 5000));
        assertTrue(ex.getMessage().contains("BLOCKED BY EXTERNAL ACCESS"));
    }

    @Test
    @DisplayName("Stage 9B Adversarial: Webhook signature verifier fails closed when appSecret is empty or signature forged")
    void testWebhookSignatureFailsClosed() {
        MetaSignatureVerifier verifier = new MetaSignatureVerifier();

        // 1. Forged signature
        assertFalse(verifier.verifySignature("{\"event\":\"test\"}", "sha256=forged_sha256_hash_value", "valid-production-secret-12345"),
                "Must reject forged payload signature");

        // 2. Invalid signature header format
        assertFalse(verifier.verifySignature("{\"event\":\"test\"}", "invalid_header", "valid-production-secret-12345"),
                "Must reject header without sha256= prefix");
    }

    private MockEnvironment createBaseProdEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("flyo.security.jwt.secret", "a-very-secure-jwt-secret-key-that-is-over-32-bytes-long!");
        env.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/flyo_prod");
        env.setProperty("spring.datasource.driverClassName", "org.postgresql.Driver");
        env.setProperty("spring.datasource.password", "EnterpriseStrengthPassword2026!#");
        env.setProperty("airline.booking.provider", "AMADEUS");
        env.setProperty("payment.vcc.provider", "STRIPE");
        env.setProperty("aviation.radar.mock-mode", "false");
        env.setProperty("flyo.seeding.enabled", "false");
        env.setProperty("spring.h2.console.enabled", "false");
        return env;
    }
}
