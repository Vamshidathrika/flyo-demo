package com.flyo.rescue.integration;

import com.flyo.rescue.integration.dto.RebookExecutionRequest;
import com.flyo.rescue.integration.dto.RebookExecutionResult;

public interface FlightRebookingProvider {
    ProviderMetadata getMetadata();
    RebookExecutionResult executeRebooking(RebookExecutionRequest request);
}
