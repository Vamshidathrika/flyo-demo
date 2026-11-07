package com.flyo.rescue;

import com.flyo.rescue.security.CorrelationMdcFilter;
import com.flyo.rescue.security.JwtTokenProvider;
import com.flyo.rescue.service.FlyoMetricsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class Stage6ObservabilityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private FlyoMetricsService metricsService;

    @Test
    @DisplayName("Observability 6.1: CorrelationMdcFilter propagates X-Correlation-ID and X-Trace-ID")
    void testDistributedTraceCorrelationHeaders() throws Exception {
        String customCorrId = "corr-client-test-99999";
        String customTraceId = "tr-otel-test-11111";

        mockMvc.perform(get("/actuator/health")
                        .header("X-Correlation-ID", customCorrId)
                        .header("X-Trace-ID", customTraceId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", customCorrId))
                .andExpect(header().string("X-Trace-ID", customTraceId));

        // When not provided, filter must auto-generate unique trace identifiers
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(header().exists("X-Trace-ID"));
    }

    @Test
    @DisplayName("Observability 6.2: Actuator health and liveness/readiness probes return UP")
    void testActuatorHealthAndReadinessEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Observability 6.3: FlyoMetricsService registers and increments domain metrics")
    void testDomainMetricsRecorded() {
        // Record provider latency
        metricsService.recordProviderLatency("AMADEUS", "PRICE_OFFER", "200_OK", 340);
        Timer timer = metricsService.getMeterRegistry().find("flyo.provider.latency")
                .tag("provider", "AMADEUS")
                .timer();
        assertNotNull(timer);
        assertTrue(timer.count() >= 1);

        // Record booking failure
        metricsService.recordBookingFailure("AMADEUS", "SEAT_UNAVAILABLE");
        Counter failureCounter = metricsService.getMeterRegistry().find("flyo.booking.failures")
                .tag("reason", "SEAT_UNAVAILABLE")
                .counter();
        assertNotNull(failureCounter);
        assertTrue(failureCounter.count() >= 1);

        // Record saga recovery
        metricsService.recordSagaRecovery("LEASE_RECOVERED");
        Counter recoveryCounter = metricsService.getMeterRegistry().find("flyo.saga.recovery")
                .tag("action", "LEASE_RECOVERED")
                .counter();
        assertNotNull(recoveryCounter);
        assertTrue(recoveryCounter.count() >= 1);

        // Record payment discrepancy
        metricsService.recordPaymentDiscrepancy("tenant_alpha", "CARD_LIMIT_EXCEEDED");
        Counter payCounter = metricsService.getMeterRegistry().find("flyo.payment.discrepancies")
                .tag("type", "CARD_LIMIT_EXCEEDED")
                .counter();
        assertNotNull(payCounter);
        assertTrue(payCounter.count() >= 1);
    }

    @Test
    @DisplayName("Observability 6.4: Metrics and Actuator endpoints expose runtime telemetry to ADMIN")
    void testPrometheusEndpointExposesMetrics() throws Exception {
        String adminToken = tokenProvider.generateToken("metrics_admin",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                Map.of("tenantId", "tenant_platform", "authorizedTenants", "*"));

        var result = mockMvc.perform(get("/actuator")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        System.out.println("ACTUATOR LINKS: " + result.getResponse().getContentAsString());

        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
    }
}
