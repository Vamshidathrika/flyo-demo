package com.flyo.rescue;

import com.flyo.rescue.controller.MockAirlineController;
import com.flyo.rescue.model.AuditEvent;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.service.AuditService;
import com.flyo.rescue.service.CaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class FailureModeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CaseService caseService;

    @Autowired
    private AuditService auditService;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.flyo.rescue.service.RebookService rebookService;

    @AfterEach
    void tearDown() {
        MockAirlineController.setBroken(false);
    }

    @Test
    @DisplayName("Chunk 8 Done-Test: Toggle break causes 500 error on confirm route")
    void testMockAirlineToggleBreak() throws Exception {
        // 1. Initial status is operational
        mockMvc.perform(get("/mock-airline/break-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broken").value(false));

        // 2. Break portal
        mockMvc.perform(post("/mock-airline/toggle-break?broken=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broken").value(true));

        // 3. Confirm page returns 500
        mockMvc.perform(get("/mock-airline/confirm"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("500 Internal Server Error")))
                .andExpect(content().string(containsString("Mock Airline Reservation Gateway Offline")));

        // 4. Restore portal
        mockMvc.perform(post("/mock-airline/toggle-break?broken=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broken").value(false));

        // 5. Confirm page returns 200
        mockMvc.perform(get("/mock-airline/confirm"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Confirm Rebooking")));
    }

    @Test
    @DisplayName("Chunk 8 Done-Test: Worker retries on broken portal, case transitions to FAILED_ESCALATED with no PNR and manual guidance")
    void testRebookFailureOnBrokenPortal() {
        MockAirlineController.setBroken(true);

        com.flyo.rescue.dto.RebookResultDto failureResult = new com.flyo.rescue.dto.RebookResultDto();
        failureResult.setSuccess(false);
        failureResult.setCaseId("C1");
        failureResult.setOptionId("O2");
        failureResult.setFlight("FL301");
        failureResult.setRetryCount(3);
        failureResult.setError("Mock Airline Reservation Gateway Offline (500)");
        org.mockito.Mockito.when(rebookService.executePlaywrightRebook(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(failureResult);

        // Setup case
        caseService.simulateCancellation("B1", "CANCELLED");
        caseService.generateAndRankOptions("C1");
        caseService.approveOption("C1", "O2", "Flyo Operator");

        // Execute rebooking on broken portal
        RescueCase result = caseService.rebookCase("C1");

        // Verifications
        assertEquals("FAILED_ESCALATED", result.getState());
        assertNull(result.getNewPnr(), "New PNR must be null upon failure — no fake PNR allowed");
        assertNotNull(result.getError());
        assertNotNull(result.getManualNextStep());
        assertTrue(result.getManualNextStep().contains("1800-209-4555"));
        assertTrue(result.getManualNextStep().contains("Ananya Sharma"));

        // Audit Trail check
        List<AuditEvent> auditEvents = auditService.getEventsForCase("C1");
        boolean hasFailedEvent = auditEvents.stream().anyMatch(e -> "REBOOK_FAILED".equals(e.getEvent()));
        assertTrue(hasFailedEvent, "Audit trail must log REBOOK_FAILED event");
    }
}
