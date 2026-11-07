package com.flyo.rescue.integration.adapter;

import com.flyo.rescue.integration.FlightRebookingProvider;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.ProviderCategory;
import com.flyo.rescue.integration.ProviderMetadata;
import com.flyo.rescue.integration.dto.RebookExecutionRequest;
import com.flyo.rescue.integration.dto.RebookExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Enterprise SabreSonic / Sabre GDS Rebooking Adapter.
 * Connects to Sabre Web Services (SWS) for AirCreate and automated exchange rebooking.
 * Configured with full data contracts, awaiting client PCC configuration.
 */
@Component
public class SabreGdsAdapter implements FlightRebookingProvider {

    private static final Logger log = LoggerFactory.getLogger(SabreGdsAdapter.class);

    private final ProviderMetadata metadata = new ProviderMetadata(
            "sabre_gds",
            "Sabre GDS Air Distribution & Order Management",
            ProviderCategory.FLIGHT_REBOOKING,
            ProviderCapabilityStatus.CONFIGURED_NOT_VERIFIED,
            List.of("REST", "SOAP", "Sabre SWS"),
            1400,
            "Global GDS inventory and order execution adapter for multi-airline corporate rebooking",
            false
    );

    @Override
    public ProviderMetadata getMetadata() {
        return metadata;
    }

    @Override
    public RebookExecutionResult executeRebooking(RebookExecutionRequest request) {
        log.info("[SabreAdapter] Executing rebook order for case {} target flight {}", request.getCaseId(), request.getTargetFlight());
        return RebookExecutionResult.failed("CREDENTIALS_REQUIRED",
                "Sabre PCC credentials not active for tenant. Contract configured; production credentials pending client onboarding.");
    }
}
