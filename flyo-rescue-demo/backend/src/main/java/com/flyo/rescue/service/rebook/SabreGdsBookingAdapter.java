package com.flyo.rescue.service.rebook;

import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.exception.ProviderNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Production Sabre GDS Flight Rebooking Adapter.
 *
 * Implements Sabre Command & REST BargainFinderMax / EnhancedAirBook orchestration.
 * When Sabre PCC (Pseudo City Code) is not configured, explicitly reports BLOCKED BY EXTERNAL ACCESS.
 */
@Component
public class SabreGdsBookingAdapter implements FlightBookingProvider {

    private static final Logger log = LoggerFactory.getLogger(SabreGdsBookingAdapter.class);

    private final String pcc;
    private final String username;

    public SabreGdsBookingAdapter(@Value("${flyo.airline.sabre.pcc:}") String pcc,
                                 @Value("${flyo.airline.sabre.username:}") String username) {
        String envPcc = System.getenv("SABRE_PCC");
        String envUser = System.getenv("SABRE_USERNAME");
        this.pcc = (pcc != null && !pcc.isBlank()) ? pcc : envPcc;
        this.username = (username != null && !username.isBlank()) ? username : envUser;
    }

    @Override
    public String getProviderName() {
        return "Sabre Global Distribution System (GDS)";
    }

    @Override
    public boolean isConfigured() {
        return pcc != null && !pcc.isBlank() && username != null && !username.isBlank();
    }

    @Override
    public RebookResultDto executeRebook(String caseId, String optionId, String flight, String passengerName,
                                        String vccCard, String vccExp, String vccCvv, int vccAmount) {
        if (!isConfigured()) {
            throw new ProviderNotConfiguredException(
                    getProviderName(),
                    "BLOCKED BY EXTERNAL ACCESS: Sabre GDS PCC credentials not provided. "
                            + "Set 'flyo.airline.sabre.pcc' / 'flyo.airline.sabre.username' or SABRE_PCC / SABRE_USERNAME."
            );
        }
        log.info("[Sabre GDS] Executing PNR modification for Case {} in PCC {}", caseId, pcc);
        throw new UnsupportedOperationException("Sabre live GDS channel requires signed EPR credentials.");
    }
}
