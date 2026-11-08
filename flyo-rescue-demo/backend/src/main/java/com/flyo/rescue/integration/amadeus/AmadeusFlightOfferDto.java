package com.flyo.rescue.integration.amadeus;

import java.util.List;

public record AmadeusFlightOfferDto(
        String offerId,
        String carrierCode,
        String flightNumber,
        String departureAirport,
        String departureTime,
        String arrivalAirport,
        String arrivalTime,
        int durationMinutes,
        int numberOfBookableSeats,
        String currency,
        double totalFareAmount
) {}
