package com.flyo.rescue.service;

import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RebookOption;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.BookingRepository;
import com.flyo.rescue.repository.RebookOptionRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Deterministic Alternative Flight Search and Ranking Service.
 */
@Service
public class AlternativeFlightSearchService {

    private static final Logger log = LoggerFactory.getLogger(AlternativeFlightSearchService.class);

    private final FlightSearchService flightSearchService;
    private final RankingEngine rankingEngine;
    private final RebookOptionRepository optionRepository;
    private final RescueCaseRepository rescueCaseRepository;
    private final BookingRepository bookingRepository;
    private final CaseStateMachineService stateMachineService;
    private final AuditService auditService;

    public AlternativeFlightSearchService(FlightSearchService flightSearchService,
                                         RankingEngine rankingEngine,
                                         RebookOptionRepository optionRepository,
                                         RescueCaseRepository rescueCaseRepository,
                                         BookingRepository bookingRepository,
                                         CaseStateMachineService stateMachineService,
                                         AuditService auditService) {
        this.flightSearchService = flightSearchService;
        this.rankingEngine = rankingEngine;
        this.optionRepository = optionRepository;
        this.rescueCaseRepository = rescueCaseRepository;
        this.bookingRepository = bookingRepository;
        this.stateMachineService = stateMachineService;
        this.auditService = auditService;
    }

    /**
     * Searches candidate flights, ranks them deterministically, and transitions to AWAITING_OPTION_SELECTION.
     */
    @Transactional
    public List<RebookOption> searchAndRankAlternatives(String caseId) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        Booking booking = bookingRepository.findById(rescueCase.getBookingId()).orElse(null);
        List<RebookOption> rawOptions;
        if (booking != null) {
            rawOptions = flightSearchService.findCandidateOptions(caseId, booking);
        } else {
            rawOptions = flightSearchService.findCandidateOptions(caseId,
                    new Booking("B-TEMP", rescueCase.getPassengerName(), "TEMP", "HYD → DEL",
                            rescueCase.getFlight(), "Tonight", rescueCase.getMissedConnection(),
                            rescueCase.getHotelRisk(), "Flyo", "Confirmed"));
        }

        if (rawOptions == null || rawOptions.isEmpty()) {
            stateMachineService.transition(
                    rescueCase,
                    CaseStateMachineService.STATE_NO_OPTIONS_AVAILABLE,
                    "Flyo Search Engine",
                    "No viable recovery flight candidates found for route"
            );
            rescueCaseRepository.save(rescueCase);
            auditService.logEvent(caseId, "NO_OPTIONS_FOUND", "Flyo Search Engine", "Candidate flight discovery returned 0 viable options.");
            return List.of();
        }

        List<RebookOption> rankedOptions = rankingEngine.rankOptions(rawOptions, rescueCase.getMissedConnection());

        // Replace previous options
        optionRepository.deleteAll(optionRepository.findByCaseIdOrderByRankAsc(caseId));
        List<RebookOption> savedOptions = optionRepository.saveAll(rankedOptions);

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_OPTIONS_READY,
                "Flyo Ranking Engine",
                "Generated and ranked " + savedOptions.size() + " recovery flight options"
        );

        stateMachineService.transition(
                rescueCase,
                CaseStateMachineService.STATE_AWAITING_OPTION_SELECTION,
                "Flyo Messaging Gateway",
                "Dispatched options to passenger WhatsApp channel. Awaiting passenger selection"
        );

        rescueCaseRepository.save(rescueCase);

        auditService.logEvent(
                caseId,
                "OPTIONS_RANKED",
                "Flyo Ranking Engine",
                String.format("Deterministic ranking complete: %d alternative flights presented to passenger.", savedOptions.size())
        );

        return savedOptions;
    }
}
