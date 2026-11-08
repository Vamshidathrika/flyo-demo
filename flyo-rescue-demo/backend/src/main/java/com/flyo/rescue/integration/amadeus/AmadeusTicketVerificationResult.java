package com.flyo.rescue.integration.amadeus;

public record AmadeusTicketVerificationResult(
        boolean valid,
        String bookingReference,
        String ticketNumber,
        String couponStatus,
        String carrier,
        String verificationRemarks,
        boolean requiresManualTicketingReview
) {
    public static AmadeusTicketVerificationResult valid(String pnr, String ticketNumber, String couponStatus, String carrier) {
        return new AmadeusTicketVerificationResult(true, pnr, ticketNumber, couponStatus, carrier,
                "Ticket coupon verified active (" + couponStatus + ") in Amadeus E-Ticket Database", false);
    }

    public static AmadeusTicketVerificationResult invalid(String pnr, String ticketNumber, String reason) {
        return new AmadeusTicketVerificationResult(false, pnr, ticketNumber, "INVALID", "UNKNOWN", reason, true);
    }

    public static AmadeusTicketVerificationResult unverified(String pnr, String ticketNumber, String reason) {
        return new AmadeusTicketVerificationResult(false, pnr, ticketNumber, "UNVERIFIED", "UNKNOWN",
                "Independent ticket verification could not reach Amadeus host: " + reason, true);
    }
}
