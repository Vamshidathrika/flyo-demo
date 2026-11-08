package com.flyo.rescue.service;

import com.flyo.rescue.model.Booking;
import com.flyo.rescue.repository.BookingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PnrIngestionService {

    private final BookingRepository bookingRepository;
    private final AuditService auditService;

    public PnrIngestionService(BookingRepository bookingRepository, AuditService auditService) {
        this.bookingRepository = bookingRepository;
        this.auditService = auditService;
    }

    /**
     * Parses unstructured e-ticket confirmation text or GDS messages and persists a structured Booking.
     */
    @Transactional
    public Booking parseAndIngest(String rawText, String source) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("Raw booking text cannot be empty.");
        }

        // 1. Extract PNR / Record Locator
        String pnr = extractPattern(rawText, "(?i)\\b(?:PNR|Record Locator|RLOC|Booking Ref(?:erence)?):?\\s*([A-Z0-9-]{5,10})\\b")
                .or(() -> extractPattern(rawText, "(?i)\\bConfirmation(?: Code)?:?\\s*([A-Z0-9-]{5,10})\\b"))
                .orElseThrow(() -> new IllegalArgumentException("Unable to extract valid PNR booking reference from provided payload."));

        // 2. Extract Passenger(s) - Supporting Multi-passenger GDS (1.1..., 2.1...)
        String passenger = extractPassengers(rawText);

        // 3. Extract Flight Segments (Multi-segment aware)
        GdsSegments parsedSegments = extractGdsSegments(rawText);

        String flight;
        String route;
        String dateTime;
        String onwardFlight;

        if (parsedSegments != null && parsedSegments.primaryFlight != null) {
            flight = parsedSegments.primaryFlight;
            route = parsedSegments.primaryRoute;
            dateTime = parsedSegments.primarySchedule;
            onwardFlight = parsedSegments.onwardFlight != null ? parsedSegments.onwardFlight : "None";
        } else {
            // Standard ticket layout
            flight = extractPattern(rawText, "(?i)\\b(?:Flight(?: Number)?):?\\s*([A-Z0-9]{2}[-\\s]?[0-9]{3,4})")
                    .or(() -> extractPattern(rawText, "\\b([A-Z]{2}[-\\s]?[0-9]{3,4})\\b"))
                    .map(this::normalizeFlightNumber)
                    .orElseThrow(() -> new IllegalArgumentException("Unable to extract flight number from booking text."));

            route = extractRoute(rawText)
                    .orElseThrow(() -> new IllegalArgumentException("Unable to extract origin and destination flight route from booking text."));

            dateTime = extractPattern(rawText, "(?i)\\b(?:Departure|Departs|Date/Time|Date|Time):?\\s*([^\\n\\r]+)")
                    .map(String::trim)
                    .orElseThrow(() -> new IllegalArgumentException("Unable to extract departure schedule date/time from booking text."));

            onwardFlight = extractPattern(rawText, "(?i)\\b(?:Onward(?: Flight)?|Connecting(?: Flight)?|Connection):?\\s*([^\\n\\r]+)")
                    .map(String::trim)
                    .orElse("None");
        }

        // Check if explicit onward flight tag overrides segment derivation
        Optional<String> explicitOnward = extractPattern(rawText, "(?i)\\b(?:Onward(?: Flight)?|Connecting(?: Flight)?|Connection):?\\s*([^\\n\\r]+)")
                .map(String::trim);
        if (explicitOnward.isPresent() && !explicitOnward.get().isBlank()) {
            onwardFlight = explicitOnward.get();
        }

        String hotelNote = extractPattern(rawText, "(?i)\\b(?:Hotel(?: Note)?|Accommodation):?\\s*([^\\n\\r]+)")
                .map(String::trim)
                .orElse("None");

        String agency = extractPattern(rawText, "(?i)\\b(?:Agency|Travel Desk|Booked by):?\\s*([^\\n\\r]+)")
                .map(String::trim)
                .orElse(source != null && !source.isBlank() ? source : "Flyo Auto-Ingest");

        // 4. Idempotent Ingestion Check
        Optional<Booking> existingOpt = bookingRepository.findByPnr(pnr);
        if (existingOpt.isPresent()) {
            Booking existing = existingOpt.get();
            existing.setPassengerName(passenger);
            existing.setFlight(flight);
            existing.setRoute(route);
            existing.setDateTime(dateTime);
            existing.setOnwardFlight(onwardFlight);
            existing.setHotelNote(hotelNote);
            existing.setAgency(agency);
            Booking updated = bookingRepository.save(existing);
            auditService.logEvent(
                    "SYSTEM",
                    "PNR_UPDATED_IDEMPOTENT",
                    "PnrIngestionEngine",
                    String.format("Idempotently updated existing booking ID %s for PNR %s (%s, flight %s).",
                            existing.getId(), pnr, passenger, flight)
            );
            return updated;
        }

        String bookingId = "B-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();

        Booking booking = new Booking(
                bookingId,
                passenger,
                pnr,
                route,
                flight,
                dateTime,
                onwardFlight,
                hotelNote,
                agency,
                "Checked-in"
        );

        Booking saved = bookingRepository.save(booking);

        auditService.logEvent(
                "SYSTEM",
                "PNR_AUTO_INGESTED",
                "PnrIngestionEngine",
                String.format("Auto-ingested PNR %s for %s (%s, flight %s) from source %s.",
                        pnr, passenger, route, flight, agency)
        );

        return saved;
    }

    private String extractPassengers(String rawText) {
        // Multi-passenger GDS format: 1.1LAST/FIRST MR \n 2.1LAST/FIRST MS
        Pattern gdsPaxPattern = Pattern.compile("(?i)\\b\\d+\\.\\d+([A-Za-z]+/[A-Za-z]+(?:\\s+(?:MR|MRS|MS|DR|PROF))?)\\b");
        Matcher m = gdsPaxPattern.matcher(rawText);
        java.util.List<String> names = new java.util.ArrayList<>();
        while (m.find()) {
            names.add(normalizePassengerName(m.group(1).trim()));
        }
        if (!names.isEmpty()) {
            return String.join(", ", names);
        }

        // Standard passenger extraction
        String rawPassenger = extractPattern(rawText, "(?i)(?:Passenger(?: Name)?|PAX|Traveler|Name):?\\s*([A-Za-z\\s/]+?)(?=\\s*(?:\\n|\\r|PNR|Flight|Route|Ticket|Date|$))")
                .or(() -> extractPattern(rawText, "(?i)([A-Za-z]+/[A-Za-z]+(?:\\s+(?:MR|MRS|MS|DR|PROF))?)"))
                .orElse("Valued Passenger");
        return normalizePassengerName(rawPassenger.trim());
    }

    private static class GdsSegments {
        String primaryFlight;
        String primaryRoute;
        String primarySchedule;
        String onwardFlight;
    }

    private GdsSegments extractGdsSegments(String rawText) {
        // GDS Segment line pattern: e.g. 1 AI 839 Y 29OCT HYDDEL HK1 2100 2315
        // or: 2 AI 101 Y 30OCT DELJFK HK1 0220 0735
        Pattern segPattern = Pattern.compile(
                "(?im)^\\s*\\d+\\s+([A-Z0-9]{2})\\s*([0-9]{3,4})\\s+[A-Z]\\s+(\\d{1,2}[A-Z]{3})\\s+([A-Z]{3})([A-Z]{3})\\s+[A-Z0-9]{2,3}\\s+(\\d{4})\\s+(\\d{4}(?:\\+\\d)?)"
        );
        Matcher matcher = segPattern.matcher(rawText);
        GdsSegments res = null;

        while (matcher.find()) {
            if (res == null) {
                res = new GdsSegments();
                String carrier = matcher.group(1).toUpperCase();
                String fNum = matcher.group(2);
                res.primaryFlight = carrier + "-" + fNum;
                String depApt = matcher.group(4).toUpperCase();
                String arrApt = matcher.group(5).toUpperCase();
                res.primaryRoute = depApt + " → " + arrApt;
                String date = matcher.group(3).toUpperCase();
                String depTimeRaw = matcher.group(6);
                String depTime = formatTime(depTimeRaw);
                String arrTimeRaw = matcher.group(7);
                String schedule = date + " " + depTime;
                if (arrTimeRaw.contains("+")) {
                    schedule += " (Overnight)";
                }
                res.primarySchedule = schedule;
            } else if (res.onwardFlight == null) {
                // Secondary segment becomes onward flight
                String carrier2 = matcher.group(1).toUpperCase();
                String fNum2 = matcher.group(2);
                String depApt2 = matcher.group(4).toUpperCase();
                String arrApt2 = matcher.group(5).toUpperCase();
                String depTime2 = formatTime(matcher.group(6));
                res.onwardFlight = carrier2 + "-" + fNum2 + " " + depApt2 + " → " + arrApt2 + " " + depTime2;
            }
        }
        return res;
    }

    private String formatTime(String rawTime) {
        if (rawTime != null && rawTime.length() == 4) {
            return rawTime.substring(0, 2) + ":" + rawTime.substring(2);
        }
        return rawTime;
    }

    private Optional<String> extractPattern(String text, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.group(1).trim());
        }
        return Optional.empty();
    }

    private Optional<String> extractRoute(String text) {
        // Pattern 1: Explicit Route: HYD -> DEL or Sector: HYD - DEL
        Pattern explicit = Pattern.compile("(?i)(?:Route|Sector):?\\s*([A-Za-z]{3,4}\\s*(?:→|->|-|to)\\s*[A-Za-z]{3,4})");
        Matcher m1 = explicit.matcher(text);
        if (m1.find()) {
            return Optional.of(normalizeRoute(m1.group(1)));
        }

        // Pattern 2: GDS segment format (e.g. 29OCT HYDDEL HK1)
        Pattern gds = Pattern.compile("(?i)\\b(?:[0-9]{1,2}[A-Z]{3})?\\s*([A-Z]{3})([A-Z]{3})\\s*(?:HK[0-9]|SS[0-9]|NN[0-9]|OK|[0-9]{4})\\b");
        Matcher mGds = gds.matcher(text);
        if (mGds.find()) {
            return Optional.of(mGds.group(1).toUpperCase() + " → " + mGds.group(2).toUpperCase());
        }

        // Pattern 3: Standalone standard airport codes: HYD -> DEL or HYD to DEL
        Pattern standard = Pattern.compile("\\b([A-Z]{3})\\s*(?:→|->|-|to)\\s*([A-Z]{3})\\b");
        Matcher m2 = standard.matcher(text);
        if (m2.find()) {
            return Optional.of(m2.group(1).toUpperCase() + " → " + m2.group(2).toUpperCase());
        }

        return Optional.empty();
    }

    private String normalizeRoute(String raw) {
        String cleaned = raw.replaceAll("(?i)\\s*to\\s*", " → ")
                .replaceAll("\\s*->\\s*", " → ")
                .replaceAll("\\s*-\\s*", " → ")
                .replaceAll("\\s*→\\s*", " → ")
                .replaceAll("\\s+", " ");
        return cleaned.toUpperCase().trim();
    }

    private String normalizeFlightNumber(String flight) {
        String clean = flight.trim().toUpperCase().replace(" ", "");
        if (!clean.contains("-") && clean.length() >= 4) {
            // e.g. AI839 -> AI-839, 6E402 -> 6E-402
            String carrier = clean.substring(0, 2);
            String number = clean.substring(2);
            return carrier + "-" + number;
        }
        return clean;
    }

    private String normalizePassengerName(String raw) {
        // Check for GDS format: SURNAME/FIRSTNAME MR or MRS
        if (raw.contains("/")) {
            String[] parts = raw.split("/");
            String lastName = parts[0].trim();
            String firstName = parts.length > 1 ? parts[1].trim() : "";

            // Strip trailing titles like MR, MRS, MS
            firstName = firstName.replaceAll("(?i)\\b(MR|MRS|MS|DR|PROF)\\b", "").trim();
            return toTitleCase(firstName + " " + lastName).trim();
        }
        return toTitleCase(raw);
    }

    private String toTitleCase(String str) {
        if (str == null || str.isBlank()) return str;
        String[] words = str.toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isBlank()) {
                sb.append(Character.toUpperCase(w.charAt(0)))
                        .append(w.substring(1))
                        .append(" ");
            }
        }
        return sb.toString().trim();
    }
}
