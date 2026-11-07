package com.flyo.rescue;

import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.service.RebookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = {"ADMIN", "SUPERVISOR", "OPERATOR"})
public class IdempotencyTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RebookService rebookService;

    @Autowired
    private com.flyo.rescue.repository.RescueCaseRepository rescueCaseRepository;

    @Autowired
    private com.flyo.rescue.repository.SimulatedMessageRepository messageRepository;

    @Autowired
    private com.flyo.rescue.repository.AuditEventRepository auditEventRepository;

    @Autowired
    private com.flyo.rescue.repository.RebookOptionRepository optionRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        auditEventRepository.deleteAll();
        optionRepository.deleteAll();
        rescueCaseRepository.findById("C1").ifPresent(c -> {
            c.setState("DETECTED");
            c.setApprovedOptionId(null);
            c.setApprovedBy(null);
            c.setApprovedAt(null);
            c.setNewPnr(null);
            rescueCaseRepository.save(c);
        });
    }

    @Test
    @DisplayName("Chunk 6 Done-Test: Approve O2 -> rebook returns R7X2K9; rebooking again returns same PNR without duplicate execution")
    void testRebookIdempotency() throws Exception {
        RebookResultDto mockResult = new RebookResultDto();
        mockResult.setSuccess(true);
        mockResult.setCaseId("C1");
        mockResult.setOptionId("O2");
        mockResult.setFlight("FL301");
        mockResult.setNewPnr("R7X2K9");
        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString())).thenReturn(mockResult);

        // 1. Simulate cancellation
        mockMvc.perform(post("/api/cases/simulate?bookingId=B1&type=CANCELLED")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // 2. Approve O2
        mockMvc.perform(post("/api/cases/C1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\": \"O2\", \"approvedBy\": \"Flyo Operator\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedOptionId", is("O2")));

        // 3. First rebook execution -> calls worker, receives R7X2K9, state DONE
        mockMvc.perform(post("/api/cases/C1/rebook")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("DONE")))
                .andExpect(jsonPath("$.newPnr", is("R7X2K9")));

        verify(rebookService, times(1)).executePlaywrightRebook("C1", "O2", "FL301");

        // 4. SECOND REBOOK EXECUTION (Idempotency Check)
        // Must return the exact same PNR R7X2K9 and state DONE without executing the worker again
        mockMvc.perform(post("/api/cases/C1/rebook")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("DONE")))
                .andExpect(jsonPath("$.newPnr", is("R7X2K9")));

        // Verify worker was STILL only called exactly 1 time!
        verify(rebookService, times(1)).executePlaywrightRebook("C1", "O2", "FL301");
    }
}
