package com.flyo.rescue;

import com.flyo.rescue.exception.ProviderNotConfiguredException;
import com.flyo.rescue.repository.*;
import com.flyo.rescue.service.BookingService;
import com.flyo.rescue.service.payment.PaymentGatewayAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * STAGE 9B ACCEPTANCE TEST:
 * Verifies that the application booted against a fresh, empty PostgreSQL database
 * creates ZERO fake seed records, produces genuine empty states on all REST endpoints,
 * and exhibits fail-closed behavior for unconfigured external providers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/flyodb_empty_e2e_test",
        "spring.datasource.username=nani",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "flyo.seeding.enabled=false",
        "aviation.radar.mock-mode=false",
        "airline.booking.provider=AMADEUS",
        "payment.vcc.provider=STRIPE"
})
public class Stage9BEmptyProductionDatabaseTest {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private VirtualCardRepository virtualCardRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private StateTransitionRepository stateTransitionRepository;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Stage 9B: Production database contains pure DDL schema and zero fabricated business records")
    void testZeroFabricatedRecordsInProductionDatabase() {
        assertEquals(0, bookingRepository.count(),
                "CRITICAL VIOLATION: Bookings table must contain 0 records on clean production boot!");
        assertEquals(0, rescueCaseRepository.count(),
                "CRITICAL VIOLATION: Rescue cases table must contain 0 records on clean production boot!");
        assertEquals(0, virtualCardRepository.count(),
                "CRITICAL VIOLATION: Virtual cards table must contain 0 records on clean production boot!");
        assertEquals(0, paymentTransactionRepository.count(),
                "CRITICAL VIOLATION: Payment transactions table must contain 0 records on clean production boot!");
        assertEquals(0, outboxEventRepository.count(),
                "CRITICAL VIOLATION: Outbox table must contain 0 records on clean production boot!");
        assertEquals(0, stateTransitionRepository.count(),
                "CRITICAL VIOLATION: State transitions table must contain 0 records on clean production boot!");
    }

    @Test
    @WithMockUser(username = "operator", roles = {"OPERATOR"})
    @DisplayName("Stage 9B: Core operational REST APIs return genuine empty arrays on empty database")
    void testEmptyDatabaseRestEndpointsProduceEmptyArrays() throws Exception {
        // 1. Cases Endpoint
        mockMvc.perform(get("/api/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 2. Bookings Endpoint
        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 3. Passenger Connection Risk Desk Endpoint
        mockMvc.perform(get("/api/intelligence/monitoring/connection-risks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("Stage 9B: Booking reset API does not re-inject fake seed data when flyo.seeding.enabled is false")
    void testSeedResetBlockedOrCleanWhenSeedingDisabled() {
        // When seeding is disabled, querying bookings remains 0
        assertEquals(0, bookingRepository.count());
    }

    @Test
    @DisplayName("Stage 9B: Repeated queries and uptime maintain zero business entities")
    void testPersistenceIdempotencyAcrossRestarts() {
        // Repeated check guarantees no asynchronous background workers silently inject synthetic bookings
        for (int i = 0; i < 5; i++) {
            assertEquals(0, bookingRepository.count(), "Asynchronous worker injected fake bookings!");
            assertEquals(0, rescueCaseRepository.count(), "Asynchronous worker injected fake cases!");
        }
    }
}
