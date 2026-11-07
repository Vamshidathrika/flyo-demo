package com.flyo.rescue.integration.amadeus;

import java.util.List;

public record AmadeusFlightOrderResponse(
        boolean success,
        String orderId,
        String bookingReference, // Airline PNR
        String ticketingStatus,  // e.g. CONFIRMED, ISSUED, PENDING
        String carrierCode,
        String flightNumber,
        String passengerName,
        String ticketNumber,
        String correlationId,
        String rawResponse,
        String errorCode,
        String errorMessage
) {
    public static AmadeusFlightOrderResponse successful(String orderId, String pnr, String ticketingStatus,
                                                        String carrierCode, String flightNumber,
                                                        String passengerName, String ticketNumber,
                                                        String correlationId) {
        return new AmadeusFlightOrderResponse(
                true, orderId, pnr, ticketingStatus, carrierCode, flightNumber,
                passengerName, ticketNumber, correlationId, null, null, null
        );
    }

    public static AmadeusFlightOrderResponse failed(String errorCode, String errorMessage, String correlationId) {
        return new AmadeusFlightOrderResponse(
                false, null, null, null, null, null, null, null,
                correlationId, null, errorCode, errorMessage
        );
    }
}
