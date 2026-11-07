package com.flyo.rescue.integration.amadeus;

import com.flyo.rescue.exception.ProviderAuthenticationException;
import com.flyo.rescue.exception.ProviderNotConfiguredException;
import com.flyo.rescue.exception.ProviderRateLimitException;
import com.flyo.rescue.exception.ProviderTimeoutException;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class AmadeusGdsSandboxClientTest {

    private HttpClient mockHttpClient;
    private HttpResponse<String> mockResponse;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockHttpClient = mock(HttpClient.class);
        mockResponse = (HttpResponse<String>) mock(HttpResponse.class);
    }

    @Test
    @DisplayName("Contract 1: Unconfigured client reports NOT_CONFIGURED and blocks external execution")
    void testUnconfiguredClientBlocksExecution() {
        AmadeusGdsSandboxClient client = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "", "", mockHttpClient);

        assertFalse(client.isConfigured());
        assertEquals(ProviderCapabilityStatus.NOT_CONFIGURED, client.getCapabilityStatus());

        ProviderNotConfiguredException ex = assertThrows(ProviderNotConfiguredException.class, client::authenticate);
        assertTrue(ex.getMessage().contains("BLOCKED BY EXTERNAL ACCESS"));
        assertTrue(ex.getMessage().contains("AMADEUS_CLIENT_ID"));
    }

    @Test
    @DisplayName("Contract 2: OAuth token acquisition and in-memory caching")
    void testOAuthTokenAcquisitionAndCaching() throws IOException, InterruptedException {
        String tokenJson = """
                {
                    "type": "amadeusOAuth2Token",
                    "username": "flyo_client@corp.com",
                    "application_name": "Flyo Rescue Demo",
                    "client_id": "test_id",
                    "token_type": "Bearer",
                    "access_token": "amadeus_mock_token_xyz_9988",
                    "expires_in": 1800,
                    "state": "approved"
                }
                """;

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(tokenJson);
        when(mockHttpClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(mockResponse);

        AmadeusGdsSandboxClient client = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "valid_client_id", "valid_secret", mockHttpClient);

        assertTrue(client.isConfigured());
        AmadeusOAuthToken token1 = client.authenticate();
        assertNotNull(token1);
        assertEquals("amadeus_mock_token_xyz_9988", token1.getAccessToken());
        assertEquals("Bearer", token1.getTokenType());
        assertFalse(token1.isExpired());

        // Second call should return cached token without dispatching second HTTP call
        AmadeusOAuthToken token2 = client.authenticate();
        assertSame(token1, token2);
        verify(mockHttpClient, times(1)).send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any());
    }

    @Test
    @DisplayName("Contract 3: Search flight offers parses IATA NDC offers")
    void testSearchFlightOffers() throws IOException, InterruptedException {
        String tokenJson = """
                {"access_token":"token_123","token_type":"Bearer","expires_in":1800}
                """;
        String offersJson = """
                {
                    "data": [
                        {
                            "type": "flight-offer",
                            "id": "OFFER-1",
                            "numberOfBookableSeats": 4,
                            "price": {"currency": "INR", "total": "5400.00"},
                            "itineraries": [
                                {
                                    "segments": [
                                        {
                                            "carrierCode": "AI",
                                            "number": "839",
                                            "departure": {"iataCode": "HYD", "at": "2026-11-20T10:00:00"},
                                            "arrival": {"iataCode": "DEL", "at": "2026-11-20T12:15:00"}
                                        }
                                    ]
                                }
                            ]
                        }
                    ]
                }
                """;

        HttpResponse<String> tokenResp = mock(HttpResponse.class);
        when(tokenResp.statusCode()).thenReturn(200);
        when(tokenResp.body()).thenReturn(tokenJson);

        HttpResponse<String> offersResp = mock(HttpResponse.class);
        when(offersResp.statusCode()).thenReturn(200);
        when(offersResp.body()).thenReturn(offersJson);

        when(mockHttpClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(tokenResp)
                .thenReturn(offersResp);

        AmadeusGdsSandboxClient client = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "cid", "sec", mockHttpClient);
        List<AmadeusFlightOfferDto> offers = client.searchFlightOffers("HYD", "DEL", "2026-11-20", 1);

        assertNotNull(offers);
        assertEquals(1, offers.size());
        AmadeusFlightOfferDto offer = offers.get(0);
        assertEquals("OFFER-1", offer.offerId());
        assertEquals("AI", offer.carrierCode());
        assertEquals("AI839", offer.flightNumber());
        assertEquals(5400.00, offer.totalFareAmount());
    }

    @Test
    @DisplayName("Contract 4: Create flight order returns authoritative PNR and e-ticket")
    void testCreateFlightOrder() throws IOException, InterruptedException {
        String tokenJson = """
                {"access_token":"token_123","token_type":"Bearer","expires_in":1800}
                """;
        String orderJson = """
                {
                    "data": {
                        "type": "flight-order",
                        "id": "eJzTd9f3djM0dAIACnMCcA==",
                        "associatedRecords": [
                            {"reference": "AMD89K", "originSystemCode": "GDS"}
                        ]
                    }
                }
                """;

        HttpResponse<String> tokenResp = mock(HttpResponse.class);
        when(tokenResp.statusCode()).thenReturn(200);
        when(tokenResp.body()).thenReturn(tokenJson);

        HttpResponse<String> orderResp = mock(HttpResponse.class);
        when(orderResp.statusCode()).thenReturn(201);
        when(orderResp.body()).thenReturn(orderJson);

        when(mockHttpClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(tokenResp)
                .thenReturn(orderResp);

        AmadeusGdsSandboxClient client = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "cid", "sec", mockHttpClient);
        AmadeusFlightOrderResponse response = client.createFlightOrder("C1", "O1", "AI839", "Ananya Sharma", "tok_123", 5000);

        assertTrue(response.success());
        assertEquals("AMD89K", response.bookingReference());
        assertEquals("098-AMD89K", response.ticketNumber());
        assertEquals("CONFIRMED", response.ticketingStatus());
    }

    @Test
    @DisplayName("Contract 5: Independent order retrieval verifies segments and coupon status")
    void testRetrieveFlightOrder() throws IOException, InterruptedException {
        String tokenJson = """
                {"access_token":"token_123","token_type":"Bearer","expires_in":1800}
                """;
        String orderJson = """
                {
                    "data": {
                        "type": "flight-order",
                        "id": "ord_12345",
                        "associatedRecords": [{"reference": "RET77Q"}],
                        "flightOffers": [
                            {
                                "itineraries": [
                                    {
                                        "segments": [
                                            {
                                                "id": "seg-1",
                                                "carrierCode": "AI",
                                                "number": "839",
                                                "departure": {"iataCode": "HYD", "at": "2026-11-20T10:00:00"},
                                                "arrival": {"iataCode": "DEL", "at": "2026-11-20T12:15:00"}
                                            }
                                        ]
                                    }
                                ]
                            }
                        ]
                    }
                }
                """;

        HttpResponse<String> tokenResp = mock(HttpResponse.class);
        when(tokenResp.statusCode()).thenReturn(200);
        when(tokenResp.body()).thenReturn(tokenJson);

        HttpResponse<String> orderResp = mock(HttpResponse.class);
        when(orderResp.statusCode()).thenReturn(200);
        when(orderResp.body()).thenReturn(orderJson);

        when(mockHttpClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(tokenResp)
                .thenReturn(orderResp);

        AmadeusGdsSandboxClient client = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "cid", "sec", mockHttpClient);
        AmadeusOrderDetailsResponse details = client.retrieveFlightOrder("ord_12345");

        assertTrue(details.found());
        assertEquals("RET77Q", details.bookingReference());
        assertEquals("CONFIRMED", details.orderStatus());
        assertEquals("OPEN", details.couponStatus());
        assertEquals(1, details.segments().size());
        assertEquals("CONFIRMED", details.segments().get(0).segmentStatus());
    }

    @Test
    @DisplayName("Contract 6: Rate limit (HTTP 429) triggers ProviderRateLimitException")
    void testRateLimitHandling() throws IOException, InterruptedException {
        when(mockResponse.statusCode()).thenReturn(429);
        when(mockResponse.body()).thenReturn("{\"errors\":[{\"status\":429,\"code\":38025,\"title\":\"Rate limit exceeded\"}]}");
        when(mockHttpClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(mockResponse);

        AmadeusGdsSandboxClient client = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "cid", "sec", mockHttpClient);
        assertThrows(ProviderRateLimitException.class, client::authenticate);
    }

    @Test
    @DisplayName("Contract 7: Network timeout triggers ProviderTimeoutException without blind retry")
    void testNetworkTimeoutHandling() throws IOException, InterruptedException {
        when(mockHttpClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new HttpTimeoutException("Connection timed out"));

        AmadeusGdsSandboxClient client = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "cid", "sec", mockHttpClient);
        assertThrows(ProviderTimeoutException.class, client::authenticate);
    }

    @Test
    @DisplayName("Contract 8: Explicit Provider Execution Mode (BLOCKED_BY_EXTERNAL_ACCESS vs SANDBOX vs LIVE)")
    void testProviderExecutionModeHonesty() {
        AmadeusGdsSandboxClient unconfigured = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "", "", mockHttpClient);
        assertEquals(AmadeusGdsSandboxClient.ProviderExecutionMode.BLOCKED_BY_EXTERNAL_ACCESS, unconfigured.getExecutionMode(),
                "Unconfigured client must be BLOCKED_BY_EXTERNAL_ACCESS, never faking live connectivity");

        AmadeusGdsSandboxClient sandbox = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "valid_id", "valid_sec", mockHttpClient);
        assertEquals(AmadeusGdsSandboxClient.ProviderExecutionMode.SANDBOX, sandbox.getExecutionMode());

        AmadeusGdsSandboxClient live = new AmadeusGdsSandboxClient("https://api.amadeus.com", "valid_id", "valid_sec", mockHttpClient);
        assertEquals(AmadeusGdsSandboxClient.ProviderExecutionMode.LIVE, live.getExecutionMode());
    }

    @Test
    @DisplayName("Contract 9: Flight pricing and cancellation capabilities")
    void testFlightPricingAndCancellation() throws IOException, InterruptedException {
        String tokenJson = "{\"access_token\":\"tok_p\",\"token_type\":\"Bearer\",\"expires_in\":1800}";
        String pricingResp = "{\"data\":{\"type\":\"flight-offers-pricing\",\"flightOffers\":[]}}";

        HttpResponse<String> tokenResp = mock(HttpResponse.class);
        when(tokenResp.statusCode()).thenReturn(200);
        when(tokenResp.body()).thenReturn(tokenJson);

        HttpResponse<String> priceResp = mock(HttpResponse.class);
        when(priceResp.statusCode()).thenReturn(200);
        when(priceResp.body()).thenReturn(pricingResp);

        HttpResponse<String> cancelResp = mock(HttpResponse.class);
        when(cancelResp.statusCode()).thenReturn(200);

        when(mockHttpClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(tokenResp)
                .thenReturn(priceResp)
                .thenReturn(cancelResp);

        AmadeusGdsSandboxClient client = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "valid_id", "valid_sec", mockHttpClient);

        java.util.Map<String, Object> pricing = client.priceFlightOffer("AI-839", 5500);
        assertEquals("CONFIRMED_PRICING", pricing.get("status"));
        assertEquals(5500, pricing.get("totalFare"));

        boolean cancelled = client.cancelFlightOrder("ord_canc_123");
        assertTrue(cancelled);
    }

    @Test
    @DisplayName("Contract 10: Circuit breaker trips OPEN on repeated upstream timeouts")
    void testCircuitBreakerTripping() throws IOException, InterruptedException {
        String tokenJson = "{\"access_token\":\"tok_cb\",\"token_type\":\"Bearer\",\"expires_in\":1800}";
        HttpResponse<String> tokenResp = mock(HttpResponse.class);
        when(tokenResp.statusCode()).thenReturn(200);
        when(tokenResp.body()).thenReturn(tokenJson);

        // First call gets token, subsequent 5 calls timeout
        when(mockHttpClient.send(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(tokenResp)
                .thenThrow(new HttpTimeoutException("Timeout 1"))
                .thenThrow(new HttpTimeoutException("Timeout 2"))
                .thenThrow(new HttpTimeoutException("Timeout 3"))
                .thenThrow(new HttpTimeoutException("Timeout 4"))
                .thenThrow(new HttpTimeoutException("Timeout 5"));

        AmadeusGdsSandboxClient client = new AmadeusGdsSandboxClient("https://test.api.amadeus.com", "cid", "sec", mockHttpClient);

        // 5 consecutive timeouts on createFlightOrder
        for (int i = 0; i < 5; i++) {
            assertThrows(ProviderTimeoutException.class, () ->
                    client.createFlightOrder("C1", "O1", "AI-101", "Pax", "tok", 5000));
        }

        // Circuit breaker must now be OPEN
        assertEquals(AmadeusGdsSandboxClient.CircuitBreakerState.OPEN, client.getCircuitBreakerState());

        // 6th call should fast-fail without touching mockHttpClient again
        com.flyo.rescue.exception.ProviderBusinessRuleException cbEx =
                assertThrows(com.flyo.rescue.exception.ProviderBusinessRuleException.class, () ->
                        client.createFlightOrder("C1", "O1", "AI-101", "Pax", "tok", 5000));
        assertEquals("CIRCUIT_BREAKER_OPEN", cbEx.getErrorCode());
        assertTrue(cbEx.getMessage().contains("Circuit breaker is OPEN"));
    }
}
