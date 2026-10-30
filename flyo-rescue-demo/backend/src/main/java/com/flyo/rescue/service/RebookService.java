package com.flyo.rescue.service;

import com.flyo.rescue.dto.RebookResultDto;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.service.rebook.FlightBookingProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class RebookService {

    private final FlightBookingProvider bookingProvider;
    private final RescueCaseRepository caseRepository;

    public RebookService(FlightBookingProvider bookingProvider, @Lazy RescueCaseRepository caseRepository) {
        this.bookingProvider = bookingProvider;
        this.caseRepository = caseRepository;
    }

    public RebookResultDto executePlaywrightRebook(String caseId, String optionId, String flight) {
        String passenger = "Ananya Sharma";
        if (caseRepository != null) {
            passenger = caseRepository.findById(caseId).map(RescueCase::getPassengerName).orElse("Ananya Sharma");
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
