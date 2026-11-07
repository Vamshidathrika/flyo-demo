package com.flyo.rescue.integration.amadeus;

import java.util.List;

public record AmadeusOrderDetailsResponse(
        boolean found,
        String orderId,
        String bookingReference,
        String orderStatus,
        List<OrderSegment> segments,
        String ticketingAgreementOption,
        String ticketNumber,
        String couponStatus
) {
    public record OrderSegment(
            String segmentId,
            String carrierCode,
            String flightNumber,
            String departureAirport,
            String arrivalAirport,
            String departureDateTime,
            String segmentStatus // e.g. CONFIRMED, WAITLISTED, CANCELLED
    ) {}

    public static AmadeusOrderDetailsResponse notFound(String orderId) {
        return new AmadeusOrderDetailsResponse(false, orderId, null, "NOT_FOUND", List.of(), null, null, null);
    }
}
