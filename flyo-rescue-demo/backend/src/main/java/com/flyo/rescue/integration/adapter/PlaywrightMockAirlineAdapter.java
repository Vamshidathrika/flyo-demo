package com.flyo.rescue.integration.adapter;

import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.integration.FlightRebookingProvider;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.ProviderCategory;
import com.flyo.rescue.integration.ProviderMetadata;
import com.flyo.rescue.integration.dto.RebookExecutionRequest;
import com.flyo.rescue.integration.dto.RebookExecutionResult;
import com.flyo.rescue.service.RebookService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Playwright Headless Browser Sandbox Adapter.
 * Connects to Flyo's local mock airline portal for integration testing and automated end-to-end demonstrations.
 * Explicitly categorized as SANDBOX_ONLY.
 */
@Component
public class PlaywrightMockAirlineAdapter implements FlightRebookingProvider {

    private final RebookService rebookService;
    private final ProviderMetadata metadata = new ProviderMetadata(
            "playwright_mock_airline",
            "Playwright Headless Browser Mock Airline Portal",
            ProviderCategory.FLIGHT_REBOOKING,
            ProviderCapabilityStatus.SANDBOX_CONNECTED,
            List.of("Playwright", "Chromium", "DOM Extraction"),
            2800,
            "Local sandbox browser worker automating simulated airline booking portal and extracting PNR",
            false
    );

    public PlaywrightMockAirlineAdapter(RebookService rebookService) {
        this.rebookService = rebookService;
    }

    @Override
    public ProviderMetadata getMetadata() {
        return metadata;
    }

    @Override
    public RebookExecutionResult executeRebooking(RebookExecutionRequest request) {
        RebookResultDto dto = rebookService.executePlaywrightRebook(
                request.getCaseId(),
                request.getOptionId(),
                request.getTargetFlight(),
                request.getPassengerName()
        );

        if (dto != null && dto.isSuccess() && dto.getNewPnr() != null) {
            return RebookExecutionResult.successful(
                    dto.getNewPnr(),
                    "ETKT-" + dto.getNewPnr(),
                    "StarSky Airlines",
                    dto.getFlight(),
                    "TXN-PLAYWRIGHT-" + System.currentTimeMillis()
            );
        } else {
            return RebookExecutionResult.failed(
                    "BROWSER_AUTOMATION_FAILED",
                    dto != null && dto.getError() != null ? dto.getError() : "Unknown Playwright rebooking error"
            );
        }
    }
}
