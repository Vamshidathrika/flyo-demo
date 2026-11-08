package com.flyo.rescue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.dto.IngestRequest;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.service.PnrIngestionService;
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

import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = {"ADMIN", "SUPERVISOR", "OPERATOR"})
public class PnrIngestionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PnrIngestionService pnrIngestionService;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    @DisplayName("PnrIngestionService parses unstructured IndiGo confirmation email")
    void testParseIndigoConfirmation() {
        String emailText = """
                IndiGo Booking Confirmation
                PNR: 6E-Z992A
                Passenger: Rahul Deshmukh
                Flight: 6E-2041
                Sector: HYD -> DEL
                Departure: Tonight 20:45
                Connecting Flight: DEL -> LHR Tomorrow 08:30
                Hotel: Aerocity Delhi booked
                Agency: Apex Corporate Travel
                """;

        Booking booking = pnrIngestionService.parseAndIngest(emailText, "Email Forwarder");

        assertNotNull(booking);
        assertNotNull(booking.getId());
        assertEquals("Rahul Deshmukh", booking.getPassengerName());
        assertEquals("6E-Z992A", booking.getPnr());
        assertEquals("6E-2041", booking.getFlight());
        assertEquals("HYD → DEL", booking.getRoute());
        assertEquals("Tonight 20:45", booking.getDateTime());
        assertEquals("DEL -> LHR Tomorrow 08:30", booking.getOnwardFlight());
        assertEquals("Aerocity Delhi booked", booking.getHotelNote());
        assertEquals("Apex Corporate Travel", booking.getAgency());

        // Verify stored in repository
        assertTrue(bookingRepository.existsById(booking.getId()));
    }

    @Test
    @DisplayName("PnrIngestionService parses GDS surname/firstname format")
    void testParseGdsFormat() {
        String gdsText = """
                1.1SHARMA/KAVYA MRS
                1 AI 839 Y 29OCT HYDDEL HK1 2100 2315
                RLOC: AI991X
                ONWARD: DEL to JFK 10:15
                AGENCY: Flyo Travel Desk
                """;

        Booking booking = pnrIngestionService.parseAndIngest(gdsText, "Sabre GDS");

        assertNotNull(booking);
        assertEquals("Kavya Sharma", booking.getPassengerName());
        assertEquals("AI991X", booking.getPnr());
        assertEquals("AI-839", booking.getFlight());
        assertEquals("HYD → DEL", booking.getRoute());
        assertEquals("DEL to JFK 10:15", booking.getOnwardFlight());
    }

    @Test
    @DisplayName("POST /api/ingest/pnr endpoint successfully creates booking")
    void testIngestApiEndpoint() throws Exception {
        String rawText = """
                E-TICKET RECEIPT
                Booking Reference: UK8820
                Passenger: Vikram Malhotra
                Flight: UK-825
                Route: HYD to CCU
                Date: Tomorrow 14:20
                """;

        IngestRequest request = new IngestRequest(rawText, "Email Ingestion");

        mockMvc.perform(post("/api/ingest/pnr")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.booking.passengerName", is("Vikram Malhotra")))
                .andExpect(jsonPath("$.booking.pnr", is("UK8820")))
                .andExpect(jsonPath("$.booking.flight", is("UK-825")))
                .andExpect(jsonPath("$.booking.route", is("HYD → CCU")));
    }

    @Test
    @DisplayName("Stage 9B: Ingests multi-passenger multi-segment GDS message with onward connection")
    void testMultiPassengerMultiSegmentGds() {
        String complexGds = """
                1.1SHARMA/KAVYA MRS  2.1SHARMA/ROHIT MR
                1 AI 839 Y 29OCT HYDDEL HK2 2100 2315
                2 AI 101 Y 30OCT DELJFK HK2 0220 0735
                RLOC: AI772P
                AGENCY: Apex Global Desk
                """;

        Booking booking = pnrIngestionService.parseAndIngest(complexGds, "Amadeus GDS");

        assertNotNull(booking);
        assertTrue(booking.getPassengerName().contains("Kavya Sharma"));
        assertTrue(booking.getPassengerName().contains("Rohit Sharma"));
        assertEquals("AI772P", booking.getPnr());
        assertEquals("AI-839", booking.getFlight());
        assertEquals("HYD → DEL", booking.getRoute());
        assertTrue(booking.getDateTime().contains("29OCT 21:00"));
        assertTrue(booking.getOnwardFlight().contains("AI-101 DEL → JFK 02:20"));
        assertEquals("Apex Global Desk", booking.getAgency());
    }

    @Test
    @DisplayName("Stage 9B: Ingests overnight flight with +1 arrival marker")
    void testOvernightFlightAndTimezone() {
        String overnightGds = """
                1.1PATEL/SNEHA MS
                1 6E 402 Y 15NOV BLRDEL HK1 2300 0145+1
                RLOC: 6E881N
                """;

        Booking booking = pnrIngestionService.parseAndIngest(overnightGds, "IndiGo direct");

        assertNotNull(booking);
        assertEquals("Sneha Patel", booking.getPassengerName());
        assertEquals("6E881N", booking.getPnr());
        assertEquals("6E-402", booking.getFlight());
        assertEquals("BLR → DEL", booking.getRoute());
        assertTrue(booking.getDateTime().contains("(Overnight)"));
    }

    @Test
    @DisplayName("Stage 9B: Duplicate PNR ingestion is idempotent and updates existing record")
    void testIdempotentDuplicateIngestion() {
        String initial = """
                E-TICKET RECEIPT
                Booking Reference: IDEM99
                Passenger: Rahul Verma
                Flight: UK-927
                Route: DEL to BOM
                Departure: 2026-11-20 18:30 IST
                """;

        Booking b1 = pnrIngestionService.parseAndIngest(initial, "Initial Agency");
        assertNotNull(b1);
        String bookingId = b1.getId();

        String update = """
                E-TICKET RECEIPT (UPDATED)
                Booking Reference: IDEM99
                Passenger: Rahul Verma
                Flight: UK-927
                Route: DEL to BOM
                Departure: 2026-11-20 19:45 IST
                Hotel: Grand Hyatt Mumbai
                Agency: Apex Corporate Travel
                """;

        Booking b2 = pnrIngestionService.parseAndIngest(update, "Updated Agency");
        assertEquals(bookingId, b2.getId(), "Should maintain identical booking ID on duplicate PNR");
        assertEquals("2026-11-20 19:45 IST", b2.getDateTime());
        assertEquals("Grand Hyatt Mumbai", b2.getHotelNote());
        assertEquals("Apex Corporate Travel", b2.getAgency());
    }

    @Test
    @DisplayName("Stage 9B: Invalid payloads without PNR or flight number fail closed with IllegalArgumentException")
    void testInvalidPnrPayloadsRejectedFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                pnrIngestionService.parseAndIngest("This is random gibberish without any airline codes.", "Test"));

        assertThrows(IllegalArgumentException.class, () ->
                pnrIngestionService.parseAndIngest("PNR: TEST1234 but no flight number here", "Test"));
    }

    @Test
    @DisplayName("POST /api/ingest/pnr with empty text returns 400 Bad Request")
    void testIngestApiValidation() throws Exception {
        IngestRequest emptyRequest = new IngestRequest("", "Email");

        mockMvc.perform(post("/api/ingest/pnr")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success", is(false)));
    }
}
