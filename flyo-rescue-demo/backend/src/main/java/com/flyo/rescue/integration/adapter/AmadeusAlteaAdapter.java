package com.flyo.rescue.integration.adapter;

import com.flyo.rescue.exception.ProviderNotConfiguredException;
import com.flyo.rescue.integration.*;
import com.flyo.rescue.integration.amadeus.AmadeusGdsSandboxClient;
import com.flyo.rescue.integration.amadeus.AmadeusOrderDetailsResponse;
import com.flyo.rescue.integration.amadeus.AmadeusTicketVerificationResult;
import com.flyo.rescue.integration.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Enterprise Amadeus Altéa PSS / GDS Integration Adapter.
 * Connects to Amadeus Web Services (1A WS) via REST / NDC 21.3.
 * Configured with full data contracts, delegating to AmadeusGdsSandboxClient when credentials are provided.
 */
@Component
public class AmadeusAlteaAdapter implements BookingRetrievalProvider, PnrLookupProvider, TicketVerificationProvider {

    private static final Logger log = LoggerFactory.getLogger(AmadeusAlteaAdapter.class);

    private final AmadeusGdsSandboxClient amadeusClient;
    private final Environment environment;

    public AmadeusAlteaAdapter(@Autowired(required = false) AmadeusGdsSandboxClient amadeusClient,
                               Environment environment) {
        this.amadeusClient = amadeusClient;
        this.environment = environment;
    }

    @Override
    public ProviderMetadata getMetadata() {
        ProviderCapabilityStatus status = (amadeusClient != null && amadeusClient.isConfigured())
                ? amadeusClient.getCapabilityStatus()
                : ProviderCapabilityStatus.CONFIGURED_NOT_VERIFIED;

        return new ProviderMetadata(
                "amadeus_altea",
                "Amadeus Altéa PSS & NDC Gateway",
                ProviderCategory.PNR_LOOKUP,
                status,
                List.of("REST", "IATA NDC 21.3", "OAuth2", "SOAP/XML"),
                650,
                "Primary airline Passenger Service System for PNR retrieval, coupon management, and ticket verification",
                false
        );
    }

    @Override
    public PassengerItinerary retrieveBooking(String bookingReference) {
        log.info("[AmadeusAdapter] Retrieving booking itinerary for reference: {}", bookingReference);
        PnrRecord segment = lookupPnr(bookingReference, "PASSENGER");
        return new PassengerItinerary(
                bookingReference,
                segment.getPassengerName(),
                List.of(segment),
                "Amadeus 1A",
                segment.getStatus()
        );
    }

    @Override
    public PnrRecord lookupPnr(String pnr, String passengerSurname) {
        log.info("[AmadeusAdapter] Looking up PNR {} for surname {}", pnr, passengerSurname);

        if (amadeusClient != null && amadeusClient.isConfigured()) {
            try {
                AmadeusOrderDetailsResponse details = amadeusClient.retrieveFlightOrder(pnr);
                if (details != null && details.success()) {
                    return new PnrRecord(
                            pnr,
                            details.passengerName() != null ? details.passengerName() : passengerSurname,
                            "",
                            "",
                            details.flightNumber() != null ? details.flightNumber() : "GDS-ORDER",
                            details.origin() + " → " + details.destination(),
                            "None",
                            details.ticketNumber() != null ? details.ticketNumber() : "TKT-PENDING",
                            "OPEN",
                            details.orderStatus() != null ? details.orderStatus() : "CONFIRMED"
                    );
                }
            } catch (Exception ex) {
                log.error("[AmadeusAdapter] Error querying Amadeus order details: {}", ex.getMessage());
            }
        }

        if (environment != null && environment.matchesProfiles("prod")) {
            throw new ProviderNotConfiguredException(
                    "Amadeus Altéa PSS",
                    "BLOCKED BY EXTERNAL ACCESS: Cannot look up PNR without active Amadeus credentials."
            );
        }

        // Test environment simulator fallback
        return new PnrRecord(
                pnr,
                passengerSurname != null ? "Customer / " + passengerSurname : "Verified Passenger",
                "+919876543210",
                "passenger@clientcorp.com",
                "AI-839",
                "HYD → DEL",
                "DEL → YYZ",
                "098-2451928410",
                "OPEN",
                "CONFIRMED"
        );
    }

    @Override
    public TicketVerificationResult verifyTicket(String pnr, String ticketNumber) {
        log.info("[AmadeusAdapter] Verifying ticket {} on PNR {}", ticketNumber, pnr);

        if (amadeusClient != null && amadeusClient.isConfigured()) {
            AmadeusTicketVerificationResult result = amadeusClient.verifyTicket(pnr, ticketNumber);
            return new TicketVerificationResult(
                    result.valid(),
                    result.bookingReference(),
                    result.ticketNumber(),
                    result.couponStatus(),
                    result.carrier(),
                    result.verificationRemarks()
            );
        }

        if (environment != null && environment.matchesProfiles("prod")) {
            throw new ProviderNotConfiguredException(
                    "Amadeus Altéa PSS",
                    "BLOCKED BY EXTERNAL ACCESS: Cannot verify ticket without active Amadeus credentials."
            );
        }

        boolean isValid = ticketNumber != null && !ticketNumber.isBlank()
                && !ticketNumber.contains("VOID") && !ticketNumber.contains("FAIL");

        String remarks = isValid
                ? "E-ticket verified active in local Amadeus contract simulator (External Amadeus sandbox blocked pending API credentials)"
                : "E-ticket failed validation: coupon marked void or invalid format";

        return new TicketVerificationResult(
                isValid,
                pnr,
                ticketNumber != null ? ticketNumber : "098-2451928410",
                isValid ? "OPEN" : "VOID",
                "Air India (AI)",
                remarks
        );
    }
}
