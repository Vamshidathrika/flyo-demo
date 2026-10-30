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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class RankingApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Chunk 3 Done-Test: GET /api/cases/{id}/options returns O2 (Rank 1), O3 (Rank 2), O1 (Rank 3)")
    void testGetCaseOptionsReturnsRankedOrder() throws Exception {
        // 1. Simulate cancellation to create C1
        mockMvc.perform(post("/api/cases/simulate?bookingId=B1&type=CANCELLED")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // 2. Fetch options for C1
        mockMvc.perform(get("/api/cases/C1/options")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                // Rank 1: O2
                .andExpect(jsonPath("$[0].id", is("O2")))
                .andExpect(jsonPath("$[0].rank", is(1)))
                .andExpect(jsonPath("$[0].flight", is("FL301")))
                .andExpect(jsonPath("$[0].savesConnection", is(true)))
                .andExpect(jsonPath("$[0].extraCost", is(4200)))
                .andExpect(jsonPath("$[0].arrival", is("Tomorrow 01:40")))
                // Rank 2: O3
                .andExpect(jsonPath("$[1].id", is("O3")))
                .andExpect(jsonPath("$[1].rank", is(2)))
                .andExpect(jsonPath("$[1].flight", is("FL401")))
                .andExpect(jsonPath("$[1].savesConnection", is(false)))
                .andExpect(jsonPath("$[1].extraCost", is(1500)))
                // Rank 3: O1
                .andExpect(jsonPath("$[2].id", is("O1")))
                .andExpect(jsonPath("$[2].rank", is(3)))
                .andExpect(jsonPath("$[2].flight", is("FL201")))
                .andExpect(jsonPath("$[2].savesConnection", is(false)))
                .andExpect(jsonPath("$[2].extraCost", is(0)))
                .andExpect(jsonPath("$[2].reason", containsString("120 minutes")));
    }
}
