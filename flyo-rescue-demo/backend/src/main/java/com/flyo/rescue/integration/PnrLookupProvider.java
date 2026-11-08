package com.flyo.rescue.integration;

import com.flyo.rescue.integration.dto.PnrRecord;

public interface PnrLookupProvider {
    ProviderMetadata getMetadata();
    PnrRecord lookupPnr(String pnr, String passengerSurname);
}
