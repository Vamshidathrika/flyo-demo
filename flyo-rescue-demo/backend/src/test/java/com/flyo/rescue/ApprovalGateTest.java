package com.flyo.rescue;

import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.service.BookingService;
import com.flyo.rescue.service.RebookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class ApprovalGateTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RebookService rebookService;

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
    @DisplayName("Chunk 4 Gate Test: Rebook before approval returns 403; Reject sets ESCALATED; Rebook on ESCALATED is 403; Approve on ESCALATED is 409 Conflict; Approved rebook succeeds")
    void testApprovalGateWorkflow() throws Exception {
        RebookResultDto mockResult = new RebookResultDto();
        mockResult.setSuccess(true);
        mockResult.setCaseId("C1");
        mockResult.setOptionId("O2");
        mockResult.setNewPnr("R7X2K9");
        when(rebookService.executePlaywrightRebook(anyString(), anyString(), anyString())).thenReturn(mockResult);

        // 1. Simulate cancellation to create C1 (State: DETECTED)
        mockMvc.perform(post("/api/cases/simulate?bookingId=B1&type=CANCELLED")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("DETECTED")));

        // 2. CRITICAL GATE TEST: Attempting rebook before approval MUST return 403 Forbidden
        mockMvc.perform(post("/api/cases/C1/rebook")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // 3. Reject flow: Reject sets ESCALATED and books nothing
        mockMvc.perform(post("/api/cases/C1/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Passenger requested full refund\", \"rejectedBy\": \"Flyo Operator\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("ESCALATED")))
                .andExpect(jsonPath("$.error", containsString("Passenger requested full refund")))
                .andExpect(jsonPath("$.approvedOptionId", nullValue()));

        // 4. Verify rebook is still blocked on ESCALATED case (403 Forbidden)
        mockMvc.perform(post("/api/cases/C1/rebook")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        // 5. CRITICAL GATE TEST: Attempting approve on ESCALATED case MUST return 409 Conflict (illegal state transition)
        mockMvc.perform(post("/api/cases/C1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\": \"O2\", \"approvedBy\": \"Flyo Operator\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", containsString("Cannot approve option on an ESCALATED case")));

        // 6. Simulate fresh case for B1 (since previous C1 is terminal/ESCALATED)
        mockMvc.perform(post("/api/cases/simulate?bookingId=B1&type=CANCELLED")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("DETECTED")));

        // 7. Approve flow on active case: Operator approves Option O2 -> moves to AWAITING_APPROVAL
        mockMvc.perform(post("/api/cases/C1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\": \"O2\", \"approvedBy\": \"Flyo Operator\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedOptionId", is("O2")))
                .andExpect(jsonPath("$.approvedBy", is("Flyo Operator")))
                .andExpect(jsonPath("$.state", is("AWAITING_APPROVAL")));

        // 8. Now rebook is permitted after approval -> moves to DONE with new PNR R7X2K9
        mockMvc.perform(post("/api/cases/C1/rebook")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("DONE")))
                .andExpect(jsonPath("$.newPnr", is("R7X2K9")))
                .andExpect(jsonPath("$.approvedOptionId", is("O2")));

        // 9. Rebook when already DONE is strictly idempotent -> returns 200 with existing PNR
        mockMvc.perform(post("/api/cases/C1/rebook")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("DONE")))
                .andExpect(jsonPath("$.newPnr", is("R7X2K9")));

        // 10. Attempting to approve after case is DONE returns 409 Conflict (illegal state transition)
        mockMvc.perform(post("/api/cases/C1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\": \"O2\", \"approvedBy\": \"Flyo Operator\"}"))
                .andExpect(status().isConflict());
    }
}
