package com.flyo.rescue.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Production Security & Secret Enforcement Validator.
 *
 * Enforces fail-closed startup behavior:
 * Prevents the application from starting in production if mandatory secrets are missing,
 * use default placeholder strings, or provide weak cryptographic keys.
 */
@Component
@Profile("prod")
public class ProductionSecurityValidator implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProductionSecurityValidator.class);

    private final Environment environment;

    @Value("${flyo.security.jwt.secret:}")
    private String jwtSecret;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    public ProductionSecurityValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("[Security Startup] Validating production secrets and security posture...");

        // 1. Validate JWT Secret
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("FATAL: JWT_SECRET environment variable is mandatory in production profile.");
        }
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("FATAL: JWT_SECRET must be at least 32 bytes (256 bits) for HMAC-SHA256 compliance.");
        }
        String lowerSecret = jwtSecret.toLowerCase();
        if (lowerSecret.contains("dummy") || lowerSecret.contains("test") || lowerSecret.contains("default") || lowerSecret.equals("flyo-production-grade-hmac-sha256-signing-secret-key-32bytes-minimum")) {
            throw new IllegalStateException("FATAL: Insecure or default JWT_SECRET detected in production profile.");
        }

        // 2. Validate Production Database Configuration (Fail-Closed: PostgreSQL Required)
        String dbUrl = environment.getProperty("spring.datasource.url", "");
        if (dbUrl.isBlank() || !dbUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("FATAL: Production profile requires PostgreSQL ('spring.datasource.url' must start with 'jdbc:postgresql:'). Found: '" + dbUrl + "'. Silent fallback to in-memory H2 is strictly forbidden.");
        }

        String driverClass = environment.getProperty("spring.datasource.driverClassName", "");
        if (!"org.postgresql.Driver".equals(driverClass)) {
            throw new IllegalStateException("FATAL: Production profile requires 'org.postgresql.Driver'. Found: '" + driverClass + "'.");
        }

        if (dbPassword == null || dbPassword.isBlank()) {
            throw new IllegalStateException("FATAL: SPRING_DATASOURCE_PASSWORD is required in production profile.");
        }
        if (dbPassword.equals("flyo_secure_password_2026") || dbPassword.equalsIgnoreCase("password") || dbPassword.equalsIgnoreCase("admin")) {
            throw new IllegalStateException("FATAL: Default fallback database password detected in production profile.");
        }

        // 3. Confirm H2 Console is Disabled
        String h2Enabled = environment.getProperty("spring.h2.console.enabled", "false");
        if (Boolean.parseBoolean(h2Enabled)) {
            throw new IllegalStateException("FATAL: H2 Console must be disabled in production profile.");
        }

        // 4. Reject MOCK and DEMO Provider Configurations
        String airlineProvider = environment.getProperty("airline.booking.provider", "").toUpperCase();
        if (airlineProvider.contains("MOCK") || airlineProvider.contains("PLAYWRIGHT") || airlineProvider.contains("DEMO")) {
            throw new IllegalStateException("FATAL: Mock airline booking provider ('" + airlineProvider + "') is strictly forbidden in production. Use AMADEUS or SABRE.");
        }

        String paymentProvider = environment.getProperty("payment.vcc.provider", "").toUpperCase();
        if (paymentProvider.contains("MOCK") || paymentProvider.contains("FAKE") || paymentProvider.contains("SIMULAT")) {
            throw new IllegalStateException("FATAL: Mock payment provider ('" + paymentProvider + "') is strictly forbidden in production. Use STRIPE or real banking provider.");
        }

        // 5. Reject Radar Mock Mode
        String radarMock = environment.getProperty("aviation.radar.mock-mode", "false");
        if (Boolean.parseBoolean(radarMock)) {
            throw new IllegalStateException("FATAL: Aviation radar mockMode is strictly forbidden in production profile.");
        }

        // 6. Reject Automatic Seed Execution in Production
        String seedEnabled = environment.getProperty("flyo.seeding.enabled", "false");
        if (Boolean.parseBoolean(seedEnabled)) {
            throw new IllegalStateException("FATAL: Synthetic database seeding ('flyo.seeding.enabled=true') is strictly forbidden in production profile.");
        }

        log.info("[Security Startup] Production secret and security posture successfully verified.");
    }
}
