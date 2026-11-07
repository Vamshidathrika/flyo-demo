package com.flyo.rescue;

import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = {"ADMIN", "SUPERVISOR", "OPERATOR"})
public class CaseSimulationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private RebookOptionRepository rebookOptionRepository;

    @Autowired
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        rescueCaseRepository.deleteAll();
        rebookOptionRepository.deleteAll();
        bookingService.resetSeedBookings();
    }

    @Test
    @DisplayName("Chunk 2 Done-Test: Simulate cancellation on B1 creates C1 in state DETECTED with correct impact assessment")
    void testSimulateCancellationOnB1() throws Exception {
        mockMvc.perform(post("/api/cases/simulate?bookingId=B1&type=CANCELLED")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("C1")))
                .andExpect(jsonPath("$.bookingId", is("B1")))
                .andExpect(jsonPath("$.passengerName", is("Ananya Sharma")))
                .andExpect(jsonPath("$.state", is("DETECTED")))
                .andExpect(jsonPath("$.cancelledSegment", containsString("HYD → DEL")))
                .andExpect(jsonPath("$.missedConnection", containsString("Toronto (YYZ)")))
                .andExpect(jsonPath("$.missedConnection", containsString("09:30")))
                .andExpect(jsonPath("$.hotelRisk", containsString("Delhi hotel non-refundable")))
                .andExpect(jsonPath("$.impact", containsString("Toronto (YYZ)")))
                .andExpect(jsonPath("$.impact", containsString("Delhi hotel non-refundable")));

        // Verify GET /api/cases/C1 returns the persisted case in state DETECTED
        mockMvc.perform(get("/api/cases/C1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("C1")))
                .andExpect(jsonPath("$.state", is("DETECTED")));
    }
}
