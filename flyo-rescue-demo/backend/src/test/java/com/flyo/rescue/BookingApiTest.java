package com.flyo.rescue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = {"ADMIN", "SUPERVISOR", "OPERATOR"})
public class BookingApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.flyo.rescue.repository.BookingRepository bookingRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        bookingRepository.findById("B1").ifPresent(b -> {
            b.setStatus("Checked-in");
            bookingRepository.save(b);
        });
    }

    @Test
    @DisplayName("Chunk 1 Done-Test: GET /api/bookings returns all 5 seeded Flyo bookings with Ananya Sharma on top")
    void testGetBookingsReturnsSeededData() throws Exception {
        mockMvc.perform(get("/api/bookings")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].id", is("B1")))
                .andExpect(jsonPath("$[0].passengerName", is("Ananya Sharma")))
                .andExpect(jsonPath("$[0].pnr", is("HYD123")))
                .andExpect(jsonPath("$[0].route", is("HYD → DEL")))
                .andExpect(jsonPath("$[0].agency", is("Flyo")))
                .andExpect(jsonPath("$[0].status", is("Checked-in")))
                .andExpect(jsonPath("$[1].id", is("B2")))
                .andExpect(jsonPath("$[2].id", is("B3")))
                .andExpect(jsonPath("$[3].id", is("B4")))
                .andExpect(jsonPath("$[4].id", is("B5")));
    }
}
