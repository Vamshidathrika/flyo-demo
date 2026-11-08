package com.flyo.rescue.integration;

import com.flyo.rescue.integration.dto.HotelReservationRequest;
import com.flyo.rescue.integration.dto.HotelVoucherResult;

public interface HotelAccommodationProvider {
    ProviderMetadata getMetadata();
    HotelVoucherResult reserveEmergencyAccommodation(HotelReservationRequest request);
}
