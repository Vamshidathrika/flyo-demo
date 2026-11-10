package com.flyo.rescue.controller;

import com.flyo.rescue.model.OperatorAccount;
import com.flyo.rescue.repository.OperatorAccountRepository;
import com.flyo.rescue.security.JwtTokenProvider;
import com.flyo.rescue.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Demo Authentication Security Boundary & Profile Isolation Tests")
public class DemoAuthenticationProfileTest {

    private OperatorAccountRepository operatorRepository;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider tokenProvider;
    private LoginRateLimiter rateLimiter;
    private MockEnvironment environment;
    private AuthController authController;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        operatorRepository = mock(OperatorAccountRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenProvider = mock(JwtTokenProvider.class);
        rateLimiter = mock(LoginRateLimiter.class);
        environment = new MockEnvironment();

        OperatorAccount demoAdmin = new OperatorAccount(
                "USR-DEMO-1",
                "admin",
                "$2a$10$hashedAdminPassword",
                "System Admin",
                "ROLE_ADMIN",
                true,
                "919999999999",
                "tenant_default",
                "*"
        );
        when(operatorRepository.findByUsername("admin")).thenReturn(Optional.of(demoAdmin));
        when(tokenProvider.generateToken(anyString(), anyList(), anyMap())).thenReturn("mock-demo-jwt-token-xyz");
        when(tokenProvider.getValidityMillis()).thenReturn(86400000L);

        authController = new AuthController(operatorRepository, passwordEncoder, tokenProvider, rateLimiter, environment);
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
    }

    @Test
    @DisplayName("Demo Profile: /api/auth/demo-session succeeds and issues valid session token")
    void testDemoProfilePermitsDemoAutoLogin() throws Exception {
        environment.setActiveProfiles("demo");

        mockMvc.perform(post("/api/auth/demo-session")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("mock-demo-jwt-token-xyz"))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.fullName").value("System Admin"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("Production Profile: /api/auth/demo-session is strictly PROHIBITED with HTTP 403 Forbidden")
    void testProdProfileProhibitsDemoAutoLogin() throws Exception {
        environment.setActiveProfiles("prod");

        mockMvc.perform(post("/api/auth/demo-session")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value(containsString("Demo authentication is only permitted under the demo profile")));

        verify(tokenProvider, never()).generateToken(any(), any(), any());
    }

    @Test
    @DisplayName("Staging Profile: /api/auth/demo-session is strictly PROHIBITED with HTTP 403 Forbidden")
    void testStagingProfileProhibitsDemoAutoLogin() throws Exception {
        environment.setActiveProfiles("staging");

        mockMvc.perform(post("/api/auth/demo-session")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value(containsString("Demo authentication is only permitted under the demo profile")));

        verify(tokenProvider, never()).generateToken(any(), any(), any());
    }

    @Test
    @DisplayName("Default (Unset) Profile: /api/auth/demo-session is strictly PROHIBITED with HTTP 403 Forbidden")
    void testDefaultProfileProhibitsDemoAutoLogin() throws Exception {
        // No active profiles set
        mockMvc.perform(post("/api/auth/demo-session")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value(containsString("Demo authentication is only permitted under the demo profile")));

        verify(tokenProvider, never()).generateToken(any(), any(), any());
    }

    @Test
    @DisplayName("Dev Profile without 'demo': /api/auth/demo-session is strictly PROHIBITED with HTTP 403 Forbidden")
    void testDevProfileWithoutDemoProhibitsDemoAutoLogin() throws Exception {
        environment.setActiveProfiles("dev");

        mockMvc.perform(post("/api/auth/demo-session")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value(containsString("Demo authentication is only permitted under the demo profile")));

        verify(tokenProvider, never()).generateToken(any(), any(), any());
    }
}
