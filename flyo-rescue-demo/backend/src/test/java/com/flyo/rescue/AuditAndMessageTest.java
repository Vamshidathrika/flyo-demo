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

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = {"ADMIN", "SUPERVISOR", "OPERATOR"})
public class AuditAndMessageTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RebookService rebookService;

    @Autowired
    private com.flyo.rescue.repository.SimulatedMessageRepository messageRepository;

    @Autowired
    private com.flyo.rescue.repository.AuditEventRepository auditEventRepository;

    @Autowired
    private com.flyo.rescue.repository.RebookOptionRepository optionRepository;

    @Autowired
    private com.flyo.rescue.repository.RescueCaseRepository rescueCaseRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        auditEventRepository.deleteAll();
        optionRepository.deleteAll();
        rescueCaseRepository.deleteById("C1");
    }

    @Test
    @DisplayName("Chunk 7 Done-Test: Draft WhatsApp message shows after options; Sent message with R7X2K9 shows after rebook; Audit trail lists full history")
    void testAuditTrailAndSimulatedWhatsAppFlow() throws Exception {
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

        // 2. Options are generated and ranked
        mockMvc.perform(get("/api/cases/C1/options")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // 3. Verify DRAFT message and Stage 1 Reassurance exist
        mockMvc.perform(get("/api/cases/C1/messages")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$[?(@.type == 'PASSENGER_STAGE_1_REASSURANCE')].text", hasItem(containsString("Please do not worry"))))
                .andExpect(jsonPath("$[?(@.type == 'DRAFT')].text", hasItem(containsString("Flyo Operations is rebooking you"))));

        // 4. Operator approves O2
        mockMvc.perform(post("/api/cases/C1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\": \"O2\", \"approvedBy\": \"Flyo Operator\"}"))
                .andExpect(status().isOk());

        // 5. Rebook executed
        mockMvc.perform(post("/api/cases/C1/rebook")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newPnr", is("R7X2K9")));

        // 6. Verify SENT_SIMULATED and Stage 2 Confirmed Ticket exist with new PNR R7X2K9
        mockMvc.perform(get("/api/cases/C1/messages")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'SENT_SIMULATED')].text", hasItem(containsString("R7X2K9"))))
                .andExpect(jsonPath("$[?(@.type == 'SENT_SIMULATED')].text", hasItem(containsString("FL301"))))
                .andExpect(jsonPath("$[?(@.type == 'PASSENGER_STAGE_2_TICKET')].text", hasItem(containsString("R7X2K9"))));

        // 7. Verify Audit Trail contains all required events
        mockMvc.perform(get("/api/cases/C1/audit")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.event == 'DISRUPTION_DETECTED')]", not(empty())))
                .andExpect(jsonPath("$[?(@.event == 'OPTIONS_RANKED')]", not(empty())))
                .andExpect(jsonPath("$[?(@.event == 'CASE_APPROVED')].actor", hasItem("Flyo Operator")))
                .andExpect(jsonPath("$[?(@.event == 'REBOOK_INITIATED')]", not(empty())))
                .andExpect(jsonPath("$[?(@.event == 'REBOOK_COMPLETED')].detail", hasItem(containsString("R7X2K9"))))
                .andExpect(jsonPath("$[?(@.event == 'MESSAGE_SENT')].detail", hasItem(containsString("R7X2K9"))));
    }
}
