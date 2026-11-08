package com.flyo.rescue;

import com.flyo.rescue.client.PredictionServiceClient;
import com.flyo.rescue.dto.FlightPredictionRequestDto;
import com.flyo.rescue.dto.FlightPredictionResponseDto;
import com.flyo.rescue.dto.ReconciledFlightEvidence;
import com.flyo.rescue.service.FlightEvidenceReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class LiveInferenceEndToEndIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PredictionServiceClient predictionClient;

    @Autowired
    private FlightEvidenceReconciliationService reconciliationService;

    @org.junit.jupiter.api.BeforeEach
    void assumeMlServiceReachable() {
        boolean reachable = false;
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("localhost", 8000), 500);
            s.close();
            reachable = true;
        } catch (Exception ignored) {
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(reachable, "Live FastAPI ML microservice on port 8000 is not reachable; skipping live inference test");
    }

    @Test
    @DisplayName("End-to-End Live: Complete chain Spring Boot -> Live FastAPI (:8000) produces real v2 predictions")
    void testLiveFastApiInferenceRoundtrip() {
        // Query through Spring Boot REST controller -> PredictionServiceClient -> Live FastAPI :8000
        ResponseEntity<FlightPredictionResponseDto> response = restTemplate.withBasicAuth("admin", "AdminSecret123!").getForEntity(
                "/api/intelligence/predict?flightNumber=UA240&carrierCode=UA&originAirport=ORD&destAirport=SFO&scheduledDepTime=18:45&scheduledArrTime=21:30&departureDate=2024-07-15&distanceMiles=1846.0&scheduledElapsedMinutes=285.0",
                FlightPredictionResponseDto.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        FlightPredictionResponseDto dto = response.getBody();
        assertNotNull(dto);

        // Verify status & identity
        assertEquals("PREDICTION_AVAILABLE", dto.getStatus());
        assertEquals("UA240", dto.getFlightNumber());
        assertEquals("UA", dto.getCarrierCode());
        assertEquals("ORD", dto.getOriginAirport());
        assertEquals("SFO", dto.getDestAirport());

        // Verify real calibrated probabilities & expected delay
        assertNotNull(dto.getCancellationProbability(), "Real model cancellation probability must not be null");
        assertTrue(dto.getCancellationProbability() >= 0.0 && dto.getCancellationProbability() <= 1.0);

        assertNotNull(dto.getDelay120mProbability(), "Real model delay >= 120m probability must not be null");
        assertTrue(dto.getDelay120mProbability() >= 0.0 && dto.getDelay120mProbability() <= 1.0);

        assertNotNull(dto.getExpectedDelayMinutes(), "Real model expected delay minutes must not be null");
        assertTrue(dto.getExpectedDelayMinutes() >= 0.0);

        // Verify metadata & safety properties
        assertTrue(dto.isExperimental(), "ML predictions must strictly be flagged experimental");
        assertNotNull(dto.getDisclaimer());
        assertTrue(dto.getDisclaimer().contains("Extreme delay >=120m"));
        assertEquals("v2.0.0-multiseason", dto.getModelVersion());
        assertTrue(dto.getInferenceLatencyMs() > 0.0, "Latency measurement must be positive");
    }

    @Test
    @DisplayName("End-to-End Live: Indian domestic flight rejected with UNSUPPORTED_AIRSPACE without network call")
    void testLiveIndianDomesticAirspaceRejection() {
        ResponseEntity<FlightPredictionResponseDto> response = restTemplate.withBasicAuth("admin", "AdminSecret123!").getForEntity(
                "/api/intelligence/predict?flightNumber=6E501&carrierCode=6E&originAirport=DEL&destAirport=BOM",
                FlightPredictionResponseDto.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        FlightPredictionResponseDto dto = response.getBody();
        assertNotNull(dto);
        assertEquals("UNSUPPORTED_AIRSPACE", dto.getStatus());
        assertNull(dto.getCancellationProbability());
        assertNull(dto.getDelay120mProbability());
    }

    @Test
    @DisplayName("End-to-End Live: US-to-International flight rejected by Live FastAPI with UNSUPPORTED_AIRSPACE")
    void testLiveUsToInternationalAirspaceRejection() {
        // AA flight JFK -> LHR (London Heathrow is outside US BTS domestic airspace)
        ResponseEntity<FlightPredictionResponseDto> response = restTemplate.withBasicAuth("admin", "AdminSecret123!").getForEntity(
                "/api/intelligence/predict?flightNumber=AA100&carrierCode=AA&originAirport=JFK&destAirport=LHR&scheduledDepTime=19:00",
                FlightPredictionResponseDto.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        FlightPredictionResponseDto dto = response.getBody();
        assertNotNull(dto);
        assertEquals("UNSUPPORTED_AIRSPACE", dto.getStatus());
        assertTrue(dto.getStatusReason().contains("LHR") || dto.getStatusReason().contains("outside US domestic BTS airspace"));
        assertNull(dto.getCancellationProbability());
    }

    @Test
    @DisplayName("End-to-End Live: Unsupported carrier on US domestic route rejected with INSUFFICIENT_COVERAGE")
    void testLiveUnsupportedCarrierRejection() {
        // Sun Country (SY) on ORD -> DFW (valid US route, but SY not in 15 BTS reporting carriers)
        ResponseEntity<FlightPredictionResponseDto> response = restTemplate.withBasicAuth("admin", "AdminSecret123!").getForEntity(
                "/api/intelligence/predict?flightNumber=SY350&carrierCode=SY&originAirport=ORD&destAirport=DFW&scheduledDepTime=16:00",
                FlightPredictionResponseDto.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        FlightPredictionResponseDto dto = response.getBody();
        assertNotNull(dto);
        assertEquals("INSUFFICIENT_COVERAGE", dto.getStatus());
        assertTrue(dto.getStatusReason().contains("SY"));
        assertNull(dto.getCancellationProbability());
    }

    @Test
    @DisplayName("End-to-End Live: Reconciled evidence incorporates Live ML prediction without changing deterministic confidence")
    void testLiveEvidenceReconciliationWithRealML() {
        ReconciledFlightEvidence evidence = reconciliationService.reconcileDisruption(
                "UA240", "ORD", "SFO", "CANCELLED", 0
        );

        assertNotNull(evidence);
        // Authoritative deterministic radar confirmation
        assertEquals(93, evidence.getConfidencePercent());
        assertTrue(evidence.isCancellationConfirmed());
        assertEquals("PROCEED_IMMEDIATE_RESCUE", evidence.getRecommendedAction());

        // Supplementary live ML prediction attached
        assertNotNull(evidence.getDisruptionPrediction());
        assertEquals("PREDICTION_AVAILABLE", evidence.getDisruptionPrediction().getStatus());
        assertNotNull(evidence.getDisruptionPrediction().getCancellationProbability());
        assertEquals("v2.0.0-multiseason", evidence.getDisruptionPrediction().getModelVersion());
    }

    @Test
    @DisplayName("End-to-End Live: Monitoring summary endpoint exposes operational ML telemetry, drift, quality, and alerts")
    void testLiveMonitoringSummaryRoundtrip() {
        @SuppressWarnings("rawtypes")
        ResponseEntity<java.util.Map> response = restTemplate.withBasicAuth("admin", "AdminSecret123!").getForEntity(
                "/api/intelligence/monitoring/summary",
                java.util.Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = response.getBody();
        assertNotNull(body);

        assertEquals("CLOSED", body.get("circuitState"));
        assertEquals(0, body.get("consecutiveFailures"));
        assertEquals(true, body.get("mlServiceHealthy"));

        // Verify monitoring blocks populated from live FastAPI
        assertNotNull(body.get("health"));
        assertNotNull(body.get("predictions"));
        assertNotNull(body.get("dataQuality"));
        assertNotNull(body.get("drift"));
        assertNotNull(body.get("alerts"));
        assertNotNull(body.get("governance"));
        assertNotNull(body.get("performance"));
    }
}
