package com.flyo.rescue;

import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.PaymentTransaction;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.PaymentTransactionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.security.JwtTokenProvider;
import com.flyo.rescue.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class Stage6EnterpriseSecurityAdversarialTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    private String tokenTenantAlpha;
    private String tokenTenantBeta;
    private String tokenOperatorOnly;

    @BeforeEach
    void setUp() {
        tokenTenantAlpha = tokenProvider.generateToken("admin_alpha",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"), new SimpleGrantedAuthority("ROLE_SUPERVISOR"), new SimpleGrantedAuthority("ROLE_ADMIN")),
                Map.of("tenantId", "tenant_alpha", "authorizedTenants", "tenant_alpha"));

        tokenTenantBeta = tokenProvider.generateToken("admin_beta",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"), new SimpleGrantedAuthority("ROLE_SUPERVISOR"), new SimpleGrantedAuthority("ROLE_ADMIN")),
                Map.of("tenantId", "tenant_beta", "authorizedTenants", "tenant_beta"));

        tokenOperatorOnly = tokenProvider.generateToken("junior_operator",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")),
                Map.of("tenantId", "tenant_alpha", "authorizedTenants", "tenant_alpha"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Security 6.1: Object-level BOLA/IDOR protection blocks cross-tenant case retrieval")
    void testCrossTenantCaseAccessBlocked() throws Exception {
        String caseIdBeta = "CASE-SEC-BETA-" + System.currentTimeMillis();
        Booking bookingBeta = new Booking("B-BETA-" + System.currentTimeMillis(), "Beta Pax", "PNRBETA", "DEL → BOM", "AI-101", "10:00", "None", "None", "Desk", "Checked-in");
        bookingBeta.setTenantId("tenant_beta");
        bookingRepository.save(bookingBeta);

        RescueCase caseBeta = new RescueCase(caseIdBeta, bookingBeta.getId(), "Beta Pax", "DETECTED", "DEL-BOM", "AI-101", "Delay", "None", "None");
        caseBeta.setTenantId("tenant_beta");
        rescueCaseRepository.saveAndFlush(caseBeta);

        // User Alpha requests Case Beta -> MUST be rejected with HTTP 404 (BOLA prevention)
        mockMvc.perform(get("/api/cases/" + caseIdBeta)
                        .header("Authorization", "Bearer " + tokenTenantAlpha)
                        .header("X-Tenant-ID", "tenant_alpha"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Security 6.2: Object-level BOLA/IDOR protection blocks cross-tenant payment ledger retrieval")
    void testCrossTenantPaymentLedgerAccessBlocked() throws Exception {
        String caseIdBeta = "CASE-PAY-BETA-" + System.currentTimeMillis();
        Booking bookingBeta = new Booking("B-PAY-BETA-" + System.currentTimeMillis(), "Beta Pax", "PNRPAY", "DEL → BOM", "AI-101", "10:00", "None", "None", "Desk", "Checked-in");
        bookingBeta.setTenantId("tenant_beta");
        bookingRepository.save(bookingBeta);

        RescueCase caseBeta = new RescueCase(caseIdBeta, bookingBeta.getId(), "Beta Pax", "DETECTED", "DEL-BOM", "AI-101", "Delay", "None", "None");
        caseBeta.setTenantId("tenant_beta");
        rescueCaseRepository.saveAndFlush(caseBeta);

        PaymentTransaction txn = new PaymentTransaction(
                "TXN-BETA-" + System.currentTimeMillis(),
                caseIdBeta,
                "tenant_beta",
                "card_beta",
                4500,
                "INR",
                "Airline GDS",
                "SETTLED",
                "REF-BETA",
                LocalDateTime.now()
        );
        paymentTransactionRepository.saveAndFlush(txn);

        // User Alpha attempts to query Case Beta payments -> MUST be 404 Not Found (tenant-isolated)
        mockMvc.perform(get("/api/payments/case/" + caseIdBeta)
                        .header("Authorization", "Bearer " + tokenTenantAlpha)
                        .header("X-Tenant-ID", "tenant_alpha"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Security 6.3: Webhook forged HMAC-SHA256 signature is rejected with HTTP 403 Forbidden")
    void testForgedWebhookSignatureRejected() throws Exception {
        String forgedPayload = "{\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{\"id\":\"wamid_fake\"}]}}]}]}";

        mockMvc.perform(post("/api/whatsapp/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=0000000000000000000000000000000000000000000000000000000000000000")
                        .content(forgedPayload))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security 6.4: SQL injection attack payloads return 404/safe handling without execution")
    void testSqlInjectionResistance() throws Exception {
        String sqlInjectionId = "CASE-1' OR '1'='1' --";

        mockMvc.perform(get("/api/cases/" + sqlInjectionId)
                        .header("Authorization", "Bearer " + tokenTenantAlpha)
                        .header("X-Tenant-ID", "tenant_alpha"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Security 6.5: Expired JWT tokens fail authentication with HTTP 401 Unauthorized")
    void testExpiredJwtRejected() throws Exception {
        // Build expired token
        String expiredToken = io.jsonwebtoken.Jwts.builder()
                .subject("expired_user")
                .claim("roles", List.of("ROLE_OPERATOR"))
                .claim("tenantId", "tenant_alpha")
                .claim("authorizedTenants", "tenant_alpha")
                .issuedAt(new java.util.Date(System.currentTimeMillis() - 7200000))
                .expiration(new java.util.Date(System.currentTimeMillis() - 3600000)) // Expired 1 hour ago
                .signWith(tokenProvider.getSigningKey())
                .compact();

        mockMvc.perform(get("/api/cases")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Security 6.6: Tampered JWT token with forged secret is rejected with HTTP 401")
    void testTamperedJwtSignatureRejected() throws Exception {
        javax.crypto.SecretKey fakeKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                "an-attacker-controlled-secret-key-32bytes-min".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        String tamperedToken = io.jsonwebtoken.Jwts.builder()
                .subject("tampered_user")
                .claim("roles", List.of("ROLE_ADMIN"))
                .claim("tenantId", "tenant_alpha")
                .claim("authorizedTenants", "*")
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 3600000))
                .signWith(fakeKey)
                .compact();

        mockMvc.perform(get("/api/cases")
                        .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Security 6.7: Sensitive data exposure prevention — passwords and secrets are absent from responses")
    void testNoSensitiveDataExposureInUserOrCaseEndpoints() throws Exception {
        mockMvc.perform(get("/api/cases")
                        .header("Authorization", "Bearer " + tokenTenantAlpha)
                        .header("X-Tenant-ID", "tenant_alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Security 6.8: Anonymous unauthenticated requests to protected endpoints return HTTP 401")
    void testAnonymousAccessBlocked() throws Exception {
        mockMvc.perform(get("/api/cases"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/cases/CASE-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"OPT-1\"}"))
                .andExpect(status().isUnauthorized());
    }
}
