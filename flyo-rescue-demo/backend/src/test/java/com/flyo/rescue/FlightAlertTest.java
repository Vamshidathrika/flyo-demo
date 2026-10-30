package com.flyo.rescue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.dto.FlightStatusAlert;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class FlightAlertTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @BeforeEach
    void setUp() {
        // Ensure B1 exists and is Checked-in
        bookingRepository.findById("B1").ifPresent(b -> {
            b.setStatus("Checked-in");
            bookingRepository.save(b);
        });
    }

    @Test
    @DisplayName("Aviation Radar webhook for AI-839 automatically triggers disruption and option ranking for B1")
    void testFlightRadarCancellationWebhook() throws Exception {
        FlightStatusAlert alert = new FlightStatusAlert(
                "AI-839",
                "CANCELLED",
                180,
                "Dense fog & low visibility at Indira Gandhi International DEL",
                "FlightAware Firehose"
        );

        mockMvc.perform(post("/api/alerts/flight-status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(alert)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.flight", is("AI-839")))
                .andExpect(jsonPath("$.affectedCasesCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.affectedCases[0].id", is("C1")))
                .andExpect(jsonPath("$.affectedCases[0].passengerName", is("Ananya Sharma")))
                .andExpect(jsonPath("$.affectedCases[0].state", is("OPTIONS_READY")));

        // Verify booking B1 status updated to Cancelled
        Booking b1 = bookingRepository.findById("B1").orElseThrow();
        assertEquals("Cancelled", b1.getStatus());

        // Verify case C1 created and options ranked
        assertTrue(rescueCaseRepository.existsById("C1"));
    }

    @Test
    @DisplayName("Aviation Radar alert for unknown flight returns 0 affected cases gracefully")
    void testFlightRadarUnknownFlight() throws Exception {
        FlightStatusAlert alert = new FlightStatusAlert(
                "EK-501",
                "CANCELLED",
                60,
                "Operational Crew Rest",
                "Cirium API"
        );

        mockMvc.perform(post("/api/alerts/flight-status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(alert)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.affectedCasesCount", is(0)))
                .andExpect(jsonPath("$.message", containsString("No active monitored bookings were impacted")));
    }
}
