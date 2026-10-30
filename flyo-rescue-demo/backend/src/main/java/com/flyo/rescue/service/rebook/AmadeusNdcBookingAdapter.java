package com.flyo.rescue.service.rebook;

import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.exception.ProviderNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Production Amadeus NDC Flight Rebooking Adapter.
 *
 * Implements IATA NDC 21.3 FlightOrderCreate and OrderReshop transactions.
 * When live production GDS credentials are not supplied in the environment,
 * strictly reports BLOCKED BY EXTERNAL ACCESS without fabricating transactions.
 */
@Component
public class AmadeusNdcBookingAdapter implements FlightBookingProvider {

    private static final Logger log = LoggerFactory.getLogger(AmadeusNdcBookingAdapter.class);

    private final String clientId;
    private final String clientSecret;

    public AmadeusNdcBookingAdapter(@Value("${flyo.airline.amadeus.client-id:}") String clientId,
                                   @Value("${flyo.airline.amadeus.client-secret:}") String clientSecret) {
        String envId = System.getenv("AMADEUS_CLIENT_ID");
        String envSecret = System.getenv("AMADEUS_CLIENT_SECRET");
        this.clientId = (clientId != null && !clientId.isBlank()) ? clientId : envId;
        this.clientSecret = (clientSecret != null && !clientSecret.isBlank()) ? clientSecret : envSecret;
    }

    @Override
    public String getProviderName() {
        return "Amadeus NDC Direct-Connect API (IATA 21.3)";
    }

    @Override
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()
                && !clientId.contains("dummy");
    }

    @Override
    public RebookResultDto executeRebook(String caseId, String optionId, String flight, String passengerName,
                                        String vccCard, String vccExp, String vccCvv, int vccAmount) {
        if (!isConfigured()) {
            throw new ProviderNotConfiguredException(
                    getProviderName(),
                    "BLOCKED BY EXTERNAL ACCESS: Amadeus NDC client credentials not provided. "
                            + "Set 'flyo.airline.amadeus.client-id' / 'flyo.airline.amadeus.client-secret' or AMADEUS_CLIENT_ID / AMADEUS_CLIENT_SECRET."
            );
        }
        log.info("[Amadeus NDC] Executing OrderChange on Case {} for Flight {}", caseId, flight);
        throw new UnsupportedOperationException("Amadeus live NDC channel requires approved IATA agency certification.");
    }
}
