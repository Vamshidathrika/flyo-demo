package com.flyo.rescue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.dto.AuthRequest;
import com.flyo.rescue.dto.AuthResponse;
import com.flyo.rescue.model.OperatorAccount;
import com.flyo.rescue.model.VirtualCard;
import com.flyo.rescue.repository.OperatorAccountRepository;
import com.flyo.rescue.repository.VirtualCardRepository;
import com.flyo.rescue.security.JwtTokenProvider;
import com.flyo.rescue.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class SecurityFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private OperatorAccountRepository operatorRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginRateLimiter rateLimiter;

    @Autowired
    private VirtualCardRepository virtualCardRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        rateLimiter.resetForTests();
        if (operatorRepository.findByUsername("sec_admin").isEmpty()) {
            operatorRepository.save(new OperatorAccount(
                    "SEC-1", "sec_admin", passwordEncoder.encode("SecAdminPass123!"),
                    "Security Admin", "ROLE_ADMIN", true, "919000000001"
            ));
        }
        if (operatorRepository.findByUsername("sec_operator").isEmpty()) {
            operatorRepository.save(new OperatorAccount(
                    "SEC-2", "sec_operator", passwordEncoder.encode("SecOpPass123!"),
                    "Security Operator", "ROLE_OPERATOR", true, "919000000002"
            ));
        }
    }

    @Test
    @DisplayName("SEC-01: Anonymous requests to protected endpoints return 401 Unauthorized")
    void testAnonymousRequestsBlocked() throws Exception {
        mockMvc.perform(get("/api/cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.status", is(401)));

        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/payments/case/C1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-02: Requests with invalid, malformed, or tampered JWT return 401")
    void testInvalidJwtRejected() throws Exception {
        mockMvc.perform(get("/api/cases")
                .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/bookings")
                .header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-03: Valid JWT Bearer token allows authenticated access to protected resources")
    void testValidJwtAllowsAccess() throws Exception {
        String token = jwtTokenProvider.generateToken(
                "sec_operator",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")),
                Map.of("fullName", "Security Operator")
        );

        mockMvc.perform(get("/api/bookings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("SEC-04: Role-Based Access Control: OPERATOR cannot access /api/payments/** (returns 403 Forbidden)")
    void testRoleBasedAccessControlForbidsNonAdmin() throws Exception {
        String operatorToken = jwtTokenProvider.generateToken(
                "sec_operator",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")),
                Map.of("fullName", "Security Operator")
        );

        mockMvc.perform(get("/api/payments/case/C1")
                .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("Forbidden")))
                .andExpect(jsonPath("$.status", is(403)));
    }

    @Test
    @DisplayName("SEC-04: Role-Based Access Control: ADMIN can access /api/payments/** (returns 200 OK)")
    void testAdminAllowedOnPayments() throws Exception {
        String adminToken = jwtTokenProvider.generateToken(
                "sec_admin",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                Map.of("fullName", "Security Admin")
        );

        mockMvc.perform(get("/api/payments/case/C1")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SEC-05: Rate Limiting: 5 consecutive failed login attempts trigger 429 Too Many Requests")
    void testLoginRateLimitingLockout() throws Exception {
        AuthRequest failedRequest = new AuthRequest("sec_admin", "WrongPassword!");
        String json = objectMapper.writeValueAsString(failedRequest);

        // 5 consecutive failed attempts
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json))
                    .andExpect(status().isUnauthorized());
        }

        // 6th attempt is locked out with HTTP 429
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.message", containsString("Too many failed login attempts")));
    }

    @Test
    @DisplayName("SEC-06: PCI Payment Data Containment: VirtualCard stores token and last-four only; zero CVV/PAN in entity")
    void testPciVirtualCardContainment() {
        VirtualCard card = new VirtualCard();
        card.setId("VCC-TEST-PCI");
        card.setCaseId("CASE-PCI-1");
        card.setOptionId("O1");
        card.setCardTokenId("tok_pci_test_token_12345");
        card.setLastFour("9876");
        card.setExpiry("12/28");
        card.setCardBrand("Visa");
        card.setAmount(5000);
        card.setCurrency("INR");
        card.setStatus("ACTIVE");
        card.setIssuedAt(LocalDateTime.now());
        VirtualCard saved = virtualCardRepository.save(card);

        assertNotNull(saved.getId());
        assertEquals("tok_pci_test_token_12345", saved.getCardTokenId());
        assertEquals("9876", saved.getLastFour());
        assertEquals("•••• •••• •••• 9876", saved.getMaskedCardNumber());

        // Forensic verification: verify reflection confirms cvv and raw cardNumber fields do not exist
        assertThrows(NoSuchFieldException.class, () -> VirtualCard.class.getDeclaredField("cvv"));
        assertThrows(NoSuchFieldException.class, () -> VirtualCard.class.getDeclaredField("cardNumber"));
    }

    @Test
    @DisplayName("SEC-07: Public endpoints (login, actuator health) accessible without authentication")
    void testPublicEndpointsAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }
}
