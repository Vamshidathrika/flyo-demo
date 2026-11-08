package com.flyo.rescue;

import com.flyo.rescue.config.AviationRadarConfig;
import com.flyo.rescue.dto.FlightStatusAlert;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.service.AviationRadarClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = {"ADMIN", "SUPERVISOR", "OPERATOR"})
public class AviationRadarClientTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AviationRadarClient radarClient;

    @Autowired
    private AviationRadarConfig radarConfig;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @BeforeEach
    void setUp() {
        // Reset B1 to Checked-in
        bookingRepository.findById("B1").ifPresent(b -> {
            b.setStatus("Checked-in");
            bookingRepository.save(b);
        });
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        bookingRepository.findById("B1").ifPresent(b -> {
            b.setStatus("Checked-in");
            bookingRepository.save(b);
        });
        rescueCaseRepository.findById("C1").ifPresent(c -> {
            c.setState("DETECTED");
            c.setApprovedOptionId(null);
            c.setApprovedBy(null);
            c.setApprovedAt(null);
            c.setNewPnr(null);
            c.setError(null);
            c.setManualNextStep(null);
            rescueCaseRepository.save(c);
        });
    }

    @Test
    @DisplayName("AviationRadarClient returns calibrated live mock telemetry for AI-839")
    void testRadarClientMockTelemetry() {
        Optional<FlightStatusAlert> alertOpt = radarClient.fetchLiveFlightStatus("AI-839");

        assertTrue(alertOpt.isPresent());
        FlightStatusAlert alert = alertOpt.get();
        assertEquals("AI-839", alert.getFlight());
        assertEquals("CANCELLED", alert.getStatus());
        assertEquals(180, alert.getDelayMinutes());
        assertTrue(alert.getReason().contains("Dense fog"));
    }

    @Test
    @DisplayName("AviationRadarClient returns empty alert for normal on-time flight")
    void testRadarClientOnTimeFlight() {
        Optional<FlightStatusAlert> alertOpt = radarClient.fetchLiveFlightStatus("UK-833");
        assertTrue(alertOpt.isEmpty());
    }

    @Test
    @DisplayName("POST /api/alerts/radar/poll scans active flights and triggers rescue pipeline")
    void testRadarPollEndpoint() throws Exception {
        mockMvc.perform(post("/api/alerts/radar/poll")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.polledCasesCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.triggeredCases[0].id", is("C1")))
                .andExpect(jsonPath("$.triggeredCases[0].passengerName", is("Ananya Sharma")));

        // Verify case C1 exists in AWAITING_APPROVAL
        assertTrue(rescueCaseRepository.existsById("C1"));
    }
}
