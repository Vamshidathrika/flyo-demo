package com.flyo.rescue;

import com.flyo.rescue.model.AuditEvent;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.repository.AuditEventRepository;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.service.BookingService;
import com.flyo.rescue.service.RankingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class ApprovalGateEdgeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private RescueCaseRepository rescueCaseRepository;

    @Autowired
    private RebookOptionRepository rebookOptionRepository;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private RankingEngine rankingEngine;

    @BeforeEach
    void setUp() {
        rescueCaseRepository.deleteAll();
        rebookOptionRepository.deleteAll();
        auditEventRepository.deleteAll();
        bookingService.resetSeedBookings();
    }

    @Test
    @DisplayName("Edge Case 1: Approving with non-existent optionId must return 400 Bad Request")
    void testInvalidOptionReturns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/cases/simulate?bookingId=B1&type=CANCELLED")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/cases/C1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\": \"INVALID_NONEXISTENT_OPTION\", \"approvedBy\": \"Flyo Operator\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    @Test
    @DisplayName("Edge Case 2: Duplicate simulate on active case is idempotent and does not duplicate audit log")
    void testDuplicateSimulateIsIdempotent() throws Exception {
        // Initial simulation
        mockMvc.perform(post("/api/cases/simulate?bookingId=B1&type=CANCELLED")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("DETECTED")));

        List<AuditEvent> initialEvents = auditEventRepository.findByCaseIdOrderByTimestampAsc("C1");
        long initialDisruptionCount = initialEvents.stream()
                .filter(e -> "DISRUPTION_DETECTED".equals(e.getEvent()))
                .count();

        // Duplicate simulation call on active case B1
        mockMvc.perform(post("/api/cases/simulate?bookingId=B1&type=CANCELLED")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("C1")))
                .andExpect(jsonPath("$.state", is("DETECTED")));

        List<AuditEvent> afterEvents = auditEventRepository.findByCaseIdOrderByTimestampAsc("C1");
        long afterDisruptionCount = afterEvents.stream()
                .filter(e -> "DISRUPTION_DETECTED".equals(e.getEvent()))
                .count();

        assertEquals(initialDisruptionCount, afterDisruptionCount, "Duplicate simulate must not duplicate DISRUPTION_DETECTED audit event");
    }

    @Test
    @DisplayName("Edge Case 3: Duplicate approve on same option is idempotent (HTTP 200)")
    void testDuplicateApproveIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/cases/simulate?bookingId=B1&type=CANCELLED")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // First approval
        mockMvc.perform(post("/api/cases/C1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\": \"O2\", \"approvedBy\": \"Flyo Operator\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("AWAITING_APPROVAL")))
                .andExpect(jsonPath("$.approvedOptionId", is("O2")));

        // Duplicate approval
        mockMvc.perform(post("/api/cases/C1/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\": \"O2\", \"approvedBy\": \"Flyo Operator\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("AWAITING_APPROVAL")))
                .andExpect(jsonPath("$.approvedOptionId", is("O2")));
    }

    @Test
    @DisplayName("Edge Case 4: Deterministic 120-minute connection threshold boundary (119m vs 120m)")
    void testRankingThreshold120mBoundary() {
        RebookOption opt119m = new RebookOption("O-119", "C_TEST", "FL119", "TEST-ROUTE", "10:00", "12:00", 119, 1000, 1, false, "");
        RebookOption opt120m = new RebookOption("O-120", "C_TEST", "FL120", "TEST-ROUTE", "10:00", "13:00", 120, 2000, 2, false, "");

        List<RebookOption> ranked = rankingEngine.rankOptions(List.of(opt119m, opt120m), "Toronto (YYZ 09:30)");

        // 120m meets minimum threshold -> Rank 1 (saves connection)
        assertEquals("O-120", ranked.get(0).getId());
        assertTrue(ranked.get(0).isSavesConnection());
        assertEquals(1, ranked.get(0).getRank());

        // 119m fails minimum threshold (<120m) -> Disqualified from Rank 1
        assertEquals("O-119", ranked.get(1).getId());
        assertFalse(ranked.get(1).isSavesConnection());
        assertEquals(2, ranked.get(1).getRank());
        assertTrue(ranked.get(1).getReason().contains("minimum safe threshold is 120 minutes"));
    }
}
