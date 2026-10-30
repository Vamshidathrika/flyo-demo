package com.flyo.rescue.service.rebook;

import com.flyo.rescue.dto.RebookResultDto;

/**
 * Universal Flight Rebooking & Ticketing Provider Interface.
 *
 * Supports GDS (Sabre, Travelport), NDC direct-connect APIs (Amadeus, Navitaire),
 * and Playwright-driven airline operations portal automation.
 */
public interface FlightBookingProvider {

    String getProviderName();

    boolean isConfigured();

    RebookResultDto executeRebook(String caseId, String optionId, String flight, String passengerName,
                                 String vccCard, String vccExp, String vccCvv, int vccAmount);
}
