package com.flyo.rescue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class MockAirlinePortalTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Chunk 5 Done-Test (Spring): Mock airline portal routes /search, /select, /confirm are live and served")
    void testMockAirlineRoutesAreServed() throws Exception {
        // 1. Search page
        mockMvc.perform(get("/mock-airline/search"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Search Flights")))
                .andExpect(content().string(containsString("HYD (Hyderabad)")));

        // 2. Select page
        mockMvc.perform(get("/mock-airline/select"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Select FL301")))
                .andExpect(content().string(containsString("FL201")))
                .andExpect(content().string(containsString("FL401")));

        // 3. Confirm page
        mockMvc.perform(get("/mock-airline/confirm?optionId=O2&flight=FL301"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Confirm Rebooking")))
                .andExpect(content().string(containsString("R7X2K9")));
    }
}
