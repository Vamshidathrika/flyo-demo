package com.flyo.rescue.integration;

import com.flyo.rescue.integration.dto.CrmSyncRequest;
import com.flyo.rescue.integration.dto.CrmSyncResult;

public interface CrmSynchronizationProvider {
    ProviderMetadata getMetadata();
    CrmSyncResult syncCase(CrmSyncRequest request);
}
