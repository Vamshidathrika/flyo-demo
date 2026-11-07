package com.flyo.rescue.integration;

import com.flyo.rescue.integration.dto.PassengerItinerary;

public interface BookingRetrievalProvider {
    ProviderMetadata getMetadata();
    PassengerItinerary retrieveBooking(String bookingReference);
}
