package com.flyo.rescue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.client.PredictionServiceClient;
import com.flyo.rescue.dto.FlightPredictionRequestDto;
import com.flyo.rescue.dto.FlightPredictionResponseDto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class PredictionServiceClientTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PredictionServiceClient predictionClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Prediction Client: Geofencing immediately returns UNSUPPORTED_AIRSPACE for Indian routes")
    void testGeofencingIndianAirspaceRejection() {
        FlightPredictionRequestDto request = new FlightPredictionRequestDto(
                "6E501", "6E", "DEL", "BOM", "08:00"
        );

        FlightPredictionResponseDto response = predictionClient.predictDisruption(request);

        assertNotNull(response);
        assertEquals("UNSUPPORTED_AIRSPACE", response.getStatus());
        assertEquals("6E501", response.getFlightNumber());
        assertNull(response.getCancellationProbability());
        assertNull(response.getDelay120mProbability());
        assertNull(response.getExpectedDelayMinutes());
        assertTrue(response.isExperimental());
        assertNotNull(response.getDisclaimer());
        assertTrue(response.getRiskFactors().stream().anyMatch(f -> f.contains("UNSUPPORTED_AIRSPACE")));
    }

    @Test
    @DisplayName("Prediction Client: Graceful fallback to MODEL_UNAVAILABLE when service is unreachable")
    void testServiceUnavailableGracefulFallback() {
        // Point to an unallocated local port with quick timeout
        PredictionServiceClient offlineClient = new PredictionServiceClient(
                true, "http://localhost:59998", 300
        );

        FlightPredictionRequestDto request = new FlightPredictionRequestDto(
                "AA100", "AA", "ORD", "DFW", "14:30"
        );

        FlightPredictionResponseDto response = offlineClient.predictDisruption(request);

        assertNotNull(response);
        assertEquals("MODEL_UNAVAILABLE", response.getStatus());
        assertEquals("AA100", response.getFlightNumber());
        assertTrue(response.getStatusReason().contains("unavailable") || response.getStatusReason().contains("timed out") || response.getStatusReason().contains("fallback"));
        assertNull(response.getCancellationProbability());
    }

    @Test
    @DisplayName("Prediction Client: Disabled configuration returns MODEL_UNAVAILABLE without network attempts")
    void testClientDisabledConfiguration() {
        PredictionServiceClient disabledClient = new PredictionServiceClient(
                false, "http://localhost:8000", 1500
        );

        FlightPredictionRequestDto request = new FlightPredictionRequestDto(
                "DL200", "DL", "ATL", "LAX", "10:00"
        );

        FlightPredictionResponseDto response = disabledClient.predictDisruption(request);

        assertNotNull(response);
        assertEquals("MODEL_UNAVAILABLE", response.getStatus());
        assertTrue(response.getStatusReason().contains("disabled via configuration"));
    }

    @Test
    @DisplayName("Prediction Client: Live HTTP inference roundtrip parses probabilities and risk levels")
    void testMockHttpInferenceSuccess() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int mockPort = server.getAddress().getPort();

        server.createContext("/predict/flight", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                FlightPredictionResponseDto mockResponse = new FlightPredictionResponseDto();
                mockResponse.setFlightNumber("UA350");
                mockResponse.setCarrierCode("UA");
                mockResponse.setOriginAirport("ORD");
                mockResponse.setDestAirport("DEN");
                mockResponse.setStatus("PREDICTION_AVAILABLE");
                mockResponse.setStatusReason("Multi-season v2 models evaluated");
                mockResponse.setCancellationProbability(0.0125);
                mockResponse.setDelay120mProbability(0.0450);
                mockResponse.setExpectedDelayMinutes(14.2);
                mockResponse.setDisruptionRiskLevel("LOW");
                mockResponse.setRiskFactors(List.of("ORIGIN_CONGESTION_HUB: ORD"));
                mockResponse.setModelVersion("v2.0.0-multiseason");
                mockResponse.setInferenceLatencyMs(12.5);

                byte[] bytes = objectMapper.writeValueAsBytes(mockResponse);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            }
        });
        server.start();

        try {
            PredictionServiceClient localClient = new PredictionServiceClient(
                    true, "http://localhost:" + mockPort, 1500
            );

            FlightPredictionRequestDto request = new FlightPredictionRequestDto(
                    "UA350", "UA", "ORD", "DEN", "12:00"
            );

            FlightPredictionResponseDto response = localClient.predictDisruption(request);

            assertNotNull(response);
            assertEquals("PREDICTION_AVAILABLE", response.getStatus());
            assertEquals("UA350", response.getFlightNumber());
            assertEquals(0.0125, response.getCancellationProbability());
            assertEquals(0.0450, response.getDelay120mProbability());
            assertEquals(14.2, response.getExpectedDelayMinutes());
            assertEquals("LOW", response.getDisruptionRiskLevel());
            assertEquals("v2.0.0-multiseason", response.getModelVersion());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Prediction Client: Timeout enforcement aborts slow calls and triggers fallback")
    void testTimeoutEnforcement() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int mockPort = server.getAddress().getPort();

        server.createContext("/predict/flight", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try {
                    Thread.sleep(800); // Exceeds client's 200ms timeout
                } catch (InterruptedException ignored) {}
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            }
        });
        server.start();

        try {
            PredictionServiceClient fastTimeoutClient = new PredictionServiceClient(
                    true, "http://localhost:" + mockPort, 200
            );

            FlightPredictionRequestDto request = new FlightPredictionRequestDto(
                    "WN500", "WN", "MDW", "LAS", "16:00"
            );

            FlightPredictionResponseDto response = fastTimeoutClient.predictDisruption(request);

            assertNotNull(response);
            assertEquals("MODEL_UNAVAILABLE", response.getStatus());
            assertTrue(response.getStatusReason().contains("timed out") || response.getStatusReason().contains("unavailable"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Controller: GET /api/intelligence/predict integrates cleanly and returns DTO")
    void testControllerEndpointIntegration() {
        ResponseEntity<FlightPredictionResponseDto> response = restTemplate.withBasicAuth("admin", "AdminSecret123!").getForEntity(
                "/api/intelligence/predict?flightNumber=6E501&carrierCode=6E&originAirport=DEL&destAirport=BOM",
                FlightPredictionResponseDto.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        FlightPredictionResponseDto dto = response.getBody();
        assertNotNull(dto);
        assertEquals("UNSUPPORTED_AIRSPACE", dto.getStatus());
        assertEquals("6E501", dto.getFlightNumber());
        assertTrue(dto.isExperimental());
    }

    @Test
    @DisplayName("Circuit Breaker: Verified state transitions CLOSED -> OPEN -> HALF_OPEN -> CLOSED")
    void testGenuineCircuitBreakerTripsOpenAndRecovers() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int mockPort = server.getAddress().getPort();
        java.util.concurrent.atomic.AtomicInteger statusCode = new java.util.concurrent.atomic.AtomicInteger(500);

        server.createContext("/predict/flight", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (statusCode.get() == 200) {
                    FlightPredictionResponseDto okResp = new FlightPredictionResponseDto();
                    okResp.setFlightNumber("AA100");
                    okResp.setStatus("PREDICTION_AVAILABLE");
                    byte[] bytes = objectMapper.writeValueAsBytes(okResp);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } else {
                    exchange.sendResponseHeaders(500, 0);
                }
                exchange.close();
            }
        });
        server.start();

        try {
            // Failure threshold: 2, Cooldown window: 300ms
            PredictionServiceClient cbClient = new PredictionServiceClient(
                    true, "http://localhost:" + mockPort, 500, 2, 300
            );

            assertEquals(PredictionServiceClient.CircuitState.CLOSED, cbClient.getCircuitState());

            FlightPredictionRequestDto req = new FlightPredictionRequestDto("AA100", "AA", "ORD", "DFW", "14:00");

            // Failure 1
            cbClient.predictDisruption(req);
            assertEquals(1, cbClient.getConsecutiveFailures());
            assertEquals(PredictionServiceClient.CircuitState.CLOSED, cbClient.getCircuitState());

            // Failure 2 -> Trips Circuit to OPEN!
            cbClient.predictDisruption(req);
            assertEquals(2, cbClient.getConsecutiveFailures());
            assertEquals(PredictionServiceClient.CircuitState.OPEN, cbClient.getCircuitState());

            // Immediate call while OPEN -> Fast fails without contacting mock server
            FlightPredictionResponseDto fastFail = cbClient.predictDisruption(req);
            assertEquals("MODEL_UNAVAILABLE", fastFail.getStatus());
            assertTrue(fastFail.getStatusReason().contains("Circuit breaker is OPEN"));
            assertEquals(PredictionServiceClient.CircuitState.OPEN, cbClient.getCircuitState());

            // Heal server and wait for cooldown (350ms > 300ms)
            statusCode.set(200);
            Thread.sleep(350);

            // Probe call transitions through HALF_OPEN -> succeeds -> resets to CLOSED!
            FlightPredictionResponseDto recovered = cbClient.predictDisruption(req);
            assertEquals("PREDICTION_AVAILABLE", recovered.getStatus());
            assertEquals(PredictionServiceClient.CircuitState.CLOSED, cbClient.getCircuitState());
            assertEquals(0, cbClient.getConsecutiveFailures());
        } finally {
            server.stop(0);
        }
    }

    @Autowired
    private com.flyo.rescue.service.FlightEvidenceReconciliationService reconciliationService;

    @Test
    @DisplayName("Evidence Reconciliation: Incorporates supplementary ML intelligence while keeping deterministic rules authoritative")
    void testReconciliationServiceIncludesSupplementaryPrediction() {
        com.flyo.rescue.dto.ReconciledFlightEvidence evidence = reconciliationService.reconcileDisruption(
                "6E501", "DEL", "BOM", "CANCELLED", 0
        );

        assertNotNull(evidence);
        // Deterministic authority preserved
        assertEquals(93, evidence.getConfidencePercent());
        assertTrue(evidence.isCancellationConfirmed());
        assertEquals("PROCEED_IMMEDIATE_RESCUE", evidence.getRecommendedAction());

        // Supplementary ML intelligence attached
        assertNotNull(evidence.getDisruptionPrediction(), "Disruption prediction must be attached as supplementary intelligence");
        assertEquals("UNSUPPORTED_AIRSPACE", evidence.getDisruptionPrediction().getStatus());
        assertTrue(evidence.getSourcesEvaluated().stream().anyMatch(s -> s.contains("Predictive ML Intelligence")));
    }
}
