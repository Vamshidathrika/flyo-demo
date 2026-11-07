package com.flyo.rescue;

import com.flyo.rescue.dto.AirportWeatherTelemetry;
import com.flyo.rescue.dto.ReconciledFlightEvidence;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.service.AviationNewsIntelligenceService;
import com.flyo.rescue.service.FlightEvidenceReconciliationService;
import com.flyo.rescue.service.OpenMeteoWeatherService;
import com.flyo.rescue.service.RankingEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class AviationIntelligenceAndWeatherTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OpenMeteoWeatherService weatherService;

    @Autowired
    private AviationNewsIntelligenceService newsService;

    @Autowired
    private FlightEvidenceReconciliationService reconciliationService;

    @Autowired
    private RankingEngine rankingEngine;

    @Test
    @DisplayName("Open-Meteo Weather: Fetches or returns resilient telemetry for Delhi (DEL)")
    void testOpenMeteoWeatherService() {
        AirportWeatherTelemetry telemetry = weatherService.getWeatherForAirport("DEL");

        assertNotNull(telemetry);
        assertEquals("DEL", telemetry.getAirportCode());
        assertTrue(telemetry.getVisibilityMeters() > 0, "Visibility must be greater than 0m");
        assertNotNull(telemetry.getWeatherCondition(), "Weather condition must be present");
        assertNotNull(telemetry.getSource(), "Source attribution must be present");

        // Verify caching: subsequent call immediately succeeds with same or cached telemetry
        AirportWeatherTelemetry cachedTelemetry = weatherService.getWeatherForAirport("DEL");
        assertNotNull(cachedTelemetry);
        assertEquals(telemetry.getAirportCode(), cachedTelemetry.getAirportCode());
    }

    @Test
    @DisplayName("Aviation News Intelligence: Retrieves operational news headlines and targeted disruption context")
    void testAviationNewsIntelligenceService() {
        List<AviationNewsIntelligenceService.AviationHeadline> headlines = newsService.getLatestHeadlines();
        assertNotNull(headlines);
        assertFalse(headlines.isEmpty(), "Operational news headlines should not be empty");

        String context = newsService.findRelevantDisruptionContext("DEL", "AI");
        assertNotNull(context);
        assertFalse(context.isBlank());
    }

    @Test
    @DisplayName("Evidence Reconciliation: Multi-source reconciliation computes high confidence for flight cancellation")
    void testFlightEvidenceReconciliationService() {
        ReconciledFlightEvidence evidence = reconciliationService.reconcileDisruption(
                "AI-101", "HYD", "DEL", "CANCELLED", 0
        );

        assertNotNull(evidence);
        assertEquals("AI-101", evidence.getFlightNumber());
        assertEquals("DEL", evidence.getArrivalAirport());
        assertTrue(evidence.getConfidencePercent() >= 90, "Confidence percent should be at least 90% for confirmed cancellation");
        assertNotNull(evidence.getConfidenceLevel());
        assertNotNull(evidence.getWeatherSummary());
        assertTrue(evidence.getSourcesEvaluated().size() >= 2, "Multiple telemetry sources must be evaluated");
        assertTrue(evidence.isCancellationConfirmed());
        assertEquals("PROCEED_IMMEDIATE_RESCUE", evidence.getRecommendedAction());
    }

    @Test
    @DisplayName("Transparent Multi-Factor Risk Scoring: Validates 120m MCT rule, risk levels, and factor annotations")
    void testRankingEngineTransparentRiskScoring() {
        List<RebookOption> candidates = List.of(
                new RebookOption("O1", "C1", "FL201", "HYD → DEL", "Tomorrow 06:00", "Tomorrow 08:05", 85, 0, false, "", 0),
                new RebookOption("O2", "C1", "FL301", "HYD → BOM → DEL (via Mumbai)", "Tonight 23:30", "Tomorrow 01:40", 470, 4200, false, "", 0),
                new RebookOption("O3", "C1", "FL401", "HYD → DEL", "Tomorrow 10:00", "Tomorrow 12:15", -165, 1500, false, "", 0)
        );

        List<RebookOption> ranked = rankingEngine.rankOptions(candidates);

        // O2 (Rank 1, 470 mins connection buffer)
        RebookOption opt2 = ranked.get(0);
        assertEquals("O2", opt2.getId());
        assertEquals(1, opt2.getRank());
        assertTrue(opt2.isSavesConnection());
        assertNotNull(opt2.getRiskScore());
        assertEquals("LOW", opt2.getRiskLevel());
        assertTrue(opt2.getRiskFactors().contains("Safe connection buffer") || opt2.getRiskFactors().contains("Generous connection buffer"));

        // O1 (Rank 3, Disqualified: 85 mins buffer < 120 mins)
        RebookOption opt1 = ranked.get(2);
        assertEquals("O1", opt1.getId());
        assertEquals(3, opt1.getRank());
        assertFalse(opt1.isSavesConnection());
        assertTrue(opt1.getRiskScore() >= 75);
        assertEquals("CRITICAL", opt1.getRiskLevel());
        assertTrue(opt1.getRiskFactors().contains("120m connection threshold"));

        // O3 (Rank 2, Missed connection)
        RebookOption opt3 = ranked.get(1);
        assertEquals("O3", opt3.getId());
        assertEquals(2, opt3.getRank());
        assertFalse(opt3.isSavesConnection());
        assertEquals("CRITICAL", opt3.getRiskLevel());
    }

    @Test
    @DisplayName("Aviation Intelligence Controller: REST endpoints /api/weather, /api/intelligence/news, /api/intelligence/reconcile return 200 OK")
    void testAviationIntelligenceControllerEndpoints() {
        String baseUrl = "http://localhost:" + port + "/api";

        // Test /api/weather/DEL
        ResponseEntity<AirportWeatherTelemetry> weatherResp = restTemplate.withBasicAuth("admin", "AdminSecret123!").getForEntity(baseUrl + "/weather/DEL", AirportWeatherTelemetry.class);
        assertEquals(HttpStatus.OK, weatherResp.getStatusCode());
        assertNotNull(weatherResp.getBody());
        assertEquals("DEL", weatherResp.getBody().getAirportCode());

        // Test /api/intelligence/news
        ResponseEntity<List> newsResp = restTemplate.withBasicAuth("admin", "AdminSecret123!").getForEntity(baseUrl + "/intelligence/news", List.class);
        assertEquals(HttpStatus.OK, newsResp.getStatusCode());
        assertNotNull(newsResp.getBody());
        assertFalse(newsResp.getBody().isEmpty());

        // Test /api/intelligence/reconcile
        ResponseEntity<ReconciledFlightEvidence> reconcileResp = restTemplate.withBasicAuth("admin", "AdminSecret123!").getForEntity(
                baseUrl + "/intelligence/reconcile?flightNumber=FL101&departureAirport=HYD&arrivalAirport=DEL&status=CANCELLED",
                ReconciledFlightEvidence.class
        );
        assertEquals(HttpStatus.OK, reconcileResp.getStatusCode());
        assertNotNull(reconcileResp.getBody());
        assertEquals("FL101", reconcileResp.getBody().getFlightNumber());
        assertTrue(reconcileResp.getBody().getConfidencePercent() >= 90);
    }

    @Test
    @DisplayName("Boundary Validation: Tests 119m (Critical Disqualification), 120m (Marginal Moderate Risk), and 121m (Tight Moderate Risk)")
    void testConnectionBufferBoundaries() {
        List<RebookOption> candidates = List.of(
                new RebookOption("O-119", "C1", "FL119", "HYD → DEL", "Tomorrow 06:00", "Tomorrow 08:05", 119, 0, false, "", 0),
                new RebookOption("O-120", "C1", "FL120", "HYD → DEL", "Tomorrow 05:59", "Tomorrow 08:04", 120, 0, false, "", 0),
                new RebookOption("O-121", "C1", "FL121", "HYD → DEL", "Tomorrow 05:58", "Tomorrow 08:03", 121, 0, false, "", 0)
        );

        List<RebookOption> ranked = rankingEngine.rankOptions(candidates);

        RebookOption opt121 = ranked.stream().filter(o -> o.getId().equals("O-121")).findFirst().orElseThrow();
        assertTrue(opt121.isSavesConnection(), "121m must save connection");
        assertEquals("MODERATE", opt121.getRiskLevel(), "121m is tight and must be flagged MODERATE, not trivially LOW");

        RebookOption opt120 = ranked.stream().filter(o -> o.getId().equals("O-120")).findFirst().orElseThrow();
        assertTrue(opt120.isSavesConnection(), "120m meets minimum threshold");
        assertEquals("MODERATE", opt120.getRiskLevel(), "120m is marginal and must be flagged MODERATE risk");

        RebookOption opt119 = ranked.stream().filter(o -> o.getId().equals("O-119")).findFirst().orElseThrow();
        assertFalse(opt119.isSavesConnection(), "119m violates 120m rule and must be disqualified");
        assertEquals("CRITICAL", opt119.getRiskLevel(), "119m must be flagged CRITICAL risk");
        assertTrue(opt119.getRiskFactors().contains("violates mandatory 120m"));
    }

    @Test
    @DisplayName("Aviation Reliability Guard: Weather and News NEVER independently establish flight cancellation without authoritative telemetry")
    void testWeatherNeverIndependentlyCancelsFlight() {
        ReconciledFlightEvidence evidence = reconciliationService.reconcileDisruption(
                "AI-555", "HYD", "DEL", "SCHEDULED", 0
        );

        assertNotNull(evidence);
        assertFalse(evidence.isCancellationConfirmed(), "Scheduled flight must NEVER be flagged as cancelled due to weather alone");
        assertNotEquals("PROCEED_IMMEDIATE_RESCUE", evidence.getRecommendedAction());
        assertTrue(evidence.getRecommendedAction().contains("MONITOR"));
    }

    @Test
    @DisplayName("Risk Scoring: Multi-hop transit stops and high hop counts add risk penalties")
    void testMultiHopTransitPenalties() {
        RebookOption direct = new RebookOption("O-DIR", "C1", "FL100", "HYD → DEL", "10:00", "12:00", 250, 0, false, "", 0);
        RebookOption multiHop = new RebookOption("O-HOP", "C1", "FL200", "HYD → BOM → BLR → DEL", "08:00", "12:00", 250, 0, false, "", 0);

        List<RebookOption> ranked = rankingEngine.rankOptions(List.of(direct, multiHop));
        RebookOption rankedDirect = ranked.stream().filter(o -> o.getId().equals("O-DIR")).findFirst().orElseThrow();
        RebookOption rankedMulti = ranked.stream().filter(o -> o.getId().equals("O-HOP")).findFirst().orElseThrow();

        assertTrue(rankedMulti.getRiskScore() > rankedDirect.getRiskScore(), "Multi-hop flight must have higher risk score than direct flight");
        assertTrue(rankedMulti.getRiskFactors().contains("transit stops"));
    }
}
