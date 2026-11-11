package com.flyo.rescue.service;

import com.flyo.rescue.integration.amadeus.AmadeusFlightOfferDto;
import com.flyo.rescue.integration.amadeus.AmadeusGdsSandboxClient;
import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.FlightSegment;
import com.flyo.rescue.model.RebookOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Candidate Flight Search and GDS Inventory Adapter.
 *
 * Implements Phase 5:
 * - Consumes canonical, validated itinerary and segment objects.
 * - Guarantees all recovery options originate from the passenger's actual stranded location.
 * - Preserves the verified final destination (never corrupts BLR -> HYD into HYD -> DEL).
 * - Attaches booking_id, incident_id, itinerary_version, source_segment_id, and deterministic cache keys.
 */
@Service
public class FlightSearchService {

    private static final Logger log = LoggerFactory.getLogger(FlightSearchService.class);

    private final AmadeusGdsSandboxClient amadeusClient;
    private final Environment environment;

    public FlightSearchService(@Autowired(required = false) AmadeusGdsSandboxClient amadeusClient,
                               Environment environment) {
        this.amadeusClient = amadeusClient;
        this.environment = environment;
    }

    public List<RebookOption> findCandidateOptions(String caseId, Booking booking) {
        return findCandidateOptions(caseId, booking, null, null);
    }

    public List<RebookOption> findCandidateOptions(String caseId, Booking booking, FlightSegment segment, String targetFinalDest) {
        if (booking == null) {
            log.warn("[FlightSearchService] Null booking provided for case {}. Returning zero candidate options.", caseId);
            return List.of();
        }

        String origin = segment != null ? segment.getOriginAirport() : booking.getOriginAirport();
        String destination = segment != null ? segment.getDestinationAirport() : booking.getDestinationAirport();
        String finalDest = targetFinalDest != null ? targetFinalDest : booking.getFinalDestination();
        String onward = booking.getOnwardFlight() != null ? booking.getOnwardFlight() : "None";
        String segmentId = segment != null ? segment.getId() : ("SEG-" + booking.getId() + "-1");
        Long itinVersion = booking.getItineraryVersion() != null ? booking.getItineraryVersion() : 1L;

        // 1. Live GDS Search via Amadeus when configured
        if (amadeusClient != null && amadeusClient.isConfigured()) {
            try {
                String searchDate = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
                log.info("[FlightSearchService] Querying live Amadeus GDS for {} -> {} on {}", origin, destination, searchDate);
                List<AmadeusFlightOfferDto> offers = amadeusClient.searchFlightOffers(origin, destination, searchDate, 1);
                if (offers != null && !offers.isEmpty()) {
                    List<RebookOption> liveOptions = new ArrayList<>();
                    int onwardDepartureMinutes = parseTimeInMinutes(onward);
                    boolean hasOnward = !onward.equalsIgnoreCase("None") && onwardDepartureMinutes > 0;

                    int rank = 1;
                    for (AmadeusFlightOfferDto offer : offers) {
                        String shortCaseId = caseId.length() > 10 ? caseId.substring(0, 10) : caseId;
                        String optId = shortCaseId + "-OPT-" + offer.offerId();
                        String flNumber = offer.carrierCode() + "-" + offer.flightNumber();
                        String flRoute = offer.departureAirport() + " → " + offer.arrivalAirport();
                        int arrMinutes = parseTimeInMinutes(offer.arrivalTime());
                        int buffer = (hasOnward && arrMinutes > 0) ? (onwardDepartureMinutes - arrMinutes) : 300;
                        boolean safe = buffer >= 120;
                        int fareDiff = (int) Math.round(offer.totalFareAmount());

                        String note = String.format("GDS Offer %s: %s departing %s arriving %s (%d seats available)",
                                offer.offerId(), flNumber, offer.departureTime(), offer.arrivalTime(), offer.numberOfBookableSeats());

                        RebookOption opt = new RebookOption(
                                optId, caseId, flNumber, flRoute,
                                offer.departureTime(), offer.arrivalTime(),
                                buffer, fareDiff, rank++, safe, note
                        );
                        attachCanonicalMetadata(opt, booking.getId(), caseId, segmentId, itinVersion, origin, offer.arrivalAirport(), finalDest);
                        liveOptions.add(opt);
                    }
                    return liveOptions;
                }
            } catch (Exception e) {
                log.error("[FlightSearchService] Amadeus flight search failed: {}. Evaluating fail-closed policy.", e.getMessage());
            }
        }

        // 2. Strict Production Guard: In production profile, never invent flights if GDS is unavailable
        if (environment != null && environment.matchesProfiles("prod")) {
            log.warn("[FlightSearchService] GDS integration unavailable in production. Returning zero candidate options.");
            return List.of();
        }

        // 3. Deterministic Development & Test Fixtures
        String o1Id = "C1".equals(caseId) ? "O1" : (caseId + "-O1");
        String o2Id = "C1".equals(caseId) ? "O2" : (caseId + "-O2");
        String o3Id = "C1".equals(caseId) ? "O3" : (caseId + "-O3");

        // Specific fixture for Ananya Sharma hero journey: HYD -> DEL -> YYZ (Toronto)
        if (origin.equalsIgnoreCase("HYD") && destination.equalsIgnoreCase("DEL") && onward.toLowerCase().contains("toronto")) {
            RebookOption opt1 = new RebookOption(
                    o1Id, caseId, "FL201", "HYD → DEL (Direct)",
                    "Tomorrow 06:00", "Tomorrow 08:05", 85, 0, 3, false,
                    "Rank 3 (Disqualified): Only 85 minutes buffer to onward flight. High risk of missed international connection."
            );
            attachCanonicalMetadata(opt1, booking.getId(), caseId, segmentId, itinVersion, "HYD", "DEL", "YYZ");
            opt1.setRecoveryStrategy("REPLACE_DISRUPTED_SEGMENT_PRESERVE_ONWARD");
            opt1.setPreservesOnwardConnection(false);
            opt1.setReroutesEntireJourney(false);
            opt1.setBaggageThroughChecked(false);

            RebookOption opt2 = new RebookOption(
                    o2Id, caseId, "FL301", "HYD → BOM → DEL (via Mumbai)",
                    "Tonight 23:30", "Tomorrow 01:40", 470, 4200, 1, true,
                    "Rank 1: Arrives Delhi Tomorrow 01:40 (7h 50m buffer). Safely preserves onward international connection to Toronto (YYZ 09:30)."
            );
            attachCanonicalMetadata(opt2, booking.getId(), caseId, segmentId, itinVersion, "HYD", "DEL", "YYZ");
            opt2.setRecoveryStrategy("REPLACE_DISRUPTED_SEGMENT_PRESERVE_ONWARD");
            opt2.setPreservesOnwardConnection(true);
            opt2.setReroutesEntireJourney(false);
            opt2.setBaggageThroughChecked(true);

            RebookOption opt3 = new RebookOption(
                    o3Id, caseId, "FL401", "HYD → DEL (Direct)",
                    "Tomorrow 10:00", "Tomorrow 12:15", -165, 1500, 2, false,
                    "Rank 2: Departs Tomorrow 10:00. Misses Toronto flight; requires schedule realignment."
            );
            attachCanonicalMetadata(opt3, booking.getId(), caseId, segmentId, itinVersion, "HYD", "DEL", "YYZ");
            opt3.setRecoveryStrategy("REBOOK_AFFECTED_REMAINING");
            opt3.setPreservesOnwardConnection(false);
            opt3.setReroutesEntireJourney(false);
            opt3.setBaggageThroughChecked(false);

            return List.of(opt1, opt2, opt3);
        }

        // Canonical dynamic generation preserving true origin and destination
        List<RebookOption> dynamicOptions = new ArrayList<>();
        int onwardDepartureMinutes = parseTimeInMinutes(onward);
        boolean hasOnward = !onward.equalsIgnoreCase("None") && onwardDepartureMinutes > 0;

        int arr1Minutes = 8 * 60 + 5;
        int buffer1 = hasOnward ? (onwardDepartureMinutes - arr1Minutes) : 360;

        // Option 1: Direct early flight replacing the disrupted segment
        RebookOption opt1 = new RebookOption(
                o1Id, caseId, "FL101", String.format("%s → %s (Direct)", origin, destination),
                "Tomorrow 06:00", "Tomorrow 08:05", buffer1, 0, 3, buffer1 >= 120,
                String.format("%s → %s early flight with %d min transit window.", origin, destination, buffer1)
        );
        attachCanonicalMetadata(opt1, booking.getId(), caseId, segmentId, itinVersion, origin, destination, finalDest);
        opt1.setRecoveryStrategy("REPLACE_DISRUPTED_SEGMENT_PRESERVE_ONWARD");
        opt1.setPreservesOnwardConnection(hasOnward && buffer1 >= 120);
        opt1.setReroutesEntireJourney(false);
        opt1.setBaggageThroughChecked(hasOnward);
        dynamicOptions.add(opt1);

        // Option 2: Express transfer option
        // If multi-segment to a different final destination (e.g. BLR -> HYD -> DEL): offer express connection to final destination
        // If standalone (e.g. BLR -> HYD): intermediate hub must still arrive at destination! E.g. BLR -> BOM -> HYD
        boolean isMultiToFinal = booking.isMultiSegment() && !destination.equalsIgnoreCase(finalDest);
        String targetDestOpt2 = isMultiToFinal ? finalDest : destination;
        String hubAirport = "BOM".equalsIgnoreCase(origin) || "BOM".equalsIgnoreCase(targetDestOpt2) ? "DEL" : "BOM";
        int arr2Minutes = 1 * 60 + 40;
        int buffer2 = hasOnward ? (onwardDepartureMinutes - arr2Minutes) : 470;

        RebookOption opt2 = new RebookOption(
                o2Id, caseId, "FL301", String.format("%s → %s → %s (Express Hub)", origin, hubAirport, targetDestOpt2),
                "Tonight 23:15", "Tomorrow 01:40", buffer2, 4200, 1, buffer2 >= 120,
                String.format("Optimal safe transfer preserving onward travel with %dh%02dm buffer.", buffer2 / 60, Math.abs(buffer2 % 60))
        );
        attachCanonicalMetadata(opt2, booking.getId(), caseId, segmentId, itinVersion, origin, targetDestOpt2, finalDest);
        if (isMultiToFinal) {
            opt2.setRecoveryStrategy("REROUTE_ENTIRE_JOURNEY");
            opt2.setPreservesOnwardConnection(false);
            opt2.setReroutesEntireJourney(true);
            opt2.setBaggageThroughChecked(true);
        } else {
            opt2.setRecoveryStrategy("REPLACE_DISRUPTED_SEGMENT_PRESERVE_ONWARD");
            opt2.setPreservesOnwardConnection(hasOnward && buffer2 >= 120);
            opt2.setReroutesEntireJourney(false);
            opt2.setBaggageThroughChecked(true);
        }
        dynamicOptions.add(opt2);

        // Option 3: Daytime direct flight
        int arr3Minutes = 12 * 60 + 15;
        int buffer3 = hasOnward ? (onwardDepartureMinutes - arr3Minutes) : 180;
        RebookOption opt3 = new RebookOption(
                o3Id, caseId, "FL401", String.format("%s → %s (Direct Daytime)", origin, destination),
                "Tomorrow 10:00", "Tomorrow 12:15", buffer3, 1500, 2, buffer3 >= 120,
                String.format("%s → %s daytime departure.", origin, destination)
        );
        attachCanonicalMetadata(opt3, booking.getId(), caseId, segmentId, itinVersion, origin, destination, finalDest);
        opt3.setRecoveryStrategy("REBOOK_AFFECTED_REMAINING");
        opt3.setPreservesOnwardConnection(false);
        opt3.setReroutesEntireJourney(false);
        opt3.setBaggageThroughChecked(false);
        dynamicOptions.add(opt3);

        return dynamicOptions;
    }

    private void attachCanonicalMetadata(RebookOption opt, String bookingId, String caseId,
                                         String segmentId, Long itineraryVersion,
                                         String origin, String dest, String finalDest) {
        opt.setBookingId(bookingId);
        opt.setIncidentId(caseId);
        opt.setSourceSegmentId(segmentId);
        opt.setItineraryVersion(itineraryVersion);
        opt.setOriginAirport(origin);
        opt.setDestinationAirport(dest);
        opt.setFinalDestination(finalDest);
        opt.setDeterministicCacheKey(String.format("%s:%s:%s:v%d", bookingId, caseId, segmentId, itineraryVersion));
    }

    private int parseTimeInMinutes(String text) {
        if (text == null || text.isBlank() || text.equalsIgnoreCase("None")) return -1;
        Pattern pattern = Pattern.compile("(\\d{1,2}):(\\d{2})");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            int hours = Integer.parseInt(matcher.group(1));
            int mins = Integer.parseInt(matcher.group(2));
            return hours * 60 + mins;
        }
        return -1;
    }
}
