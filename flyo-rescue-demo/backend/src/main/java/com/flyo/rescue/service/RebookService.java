package com.flyo.rescue.service;

import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.service.rebook.AmadeusNdcBookingAdapter;
import com.flyo.rescue.service.rebook.FlightBookingProvider;
import com.flyo.rescue.service.rebook.PartnerPortalPlaywrightAdapter;
import com.flyo.rescue.service.rebook.SabreGdsBookingAdapter;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RebookService {

    private final FlightBookingProvider bookingProvider;
    private final RescueCaseRepository caseRepository;

    public RebookService(List<FlightBookingProvider> providers,
                         Environment environment,
                         @Lazy RescueCaseRepository caseRepository) {
        String configuredProvider = environment.getProperty("airline.booking.provider", "SANDBOX").toUpperCase();
        FlightBookingProvider selected = null;
        for (FlightBookingProvider p : providers) {
            if ("AMADEUS".equals(configuredProvider) && p instanceof AmadeusNdcBookingAdapter) {
                selected = p;
                break;
            } else if ("SABRE".equals(configuredProvider) && p instanceof SabreGdsBookingAdapter) {
                selected = p;
                break;
            } else if ("SANDBOX".equals(configuredProvider) && p instanceof PartnerPortalPlaywrightAdapter) {
                selected = p;
                break;
            }
        }
        if (selected == null) {
            selected = providers.stream()
                    .filter(p -> p instanceof PartnerPortalPlaywrightAdapter)
                    .findFirst()
                    .orElse(providers.get(0));
        }
        this.bookingProvider = selected;
        this.caseRepository = caseRepository;
    }

    public RebookResultDto executePlaywrightRebook(String caseId, String optionId, String flight) {
        String passenger = "Verified Passenger";
        if (caseRepository != null) {
            passenger = caseRepository.findById(caseId).map(RescueCase::getPassengerName).orElse("Verified Passenger");
        }
        return executePlaywrightRebook(caseId, optionId, flight, passenger);
    }

    public RebookResultDto executePlaywrightRebook(String caseId, String optionId, String flight, String passengerName) {
        return executePlaywrightRebook(caseId, optionId, flight, passengerName, null, null, null, 0);
    }

    public RebookResultDto executePlaywrightRebook(String caseId, String optionId, String flight, String passengerName,
                                                  String vccCard, String vccExp, String vccCvv, int vccAmount) {
        return bookingProvider.executeRebook(caseId, optionId, flight, passengerName, vccCard, vccExp, vccCvv, vccAmount);
    }

    public String getActiveBookingProviderName() {
        return bookingProvider.getProviderName();
    }
}
