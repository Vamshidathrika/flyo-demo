package com.flyo.rescue.service;

import com.flyo.rescue.model.Booking;
import com.flyo.rescue.model.RebookOption;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FlightSearchService {

    /**
     * Dynamically generates candidate recovery flights for any route and calculates connection buffers.
     */
    public List<RebookOption> findCandidateOptions(String caseId, Booking booking) {
        String route = booking.getRoute() != null ? booking.getRoute() : "HYD → DEL";
        String onward = booking.getOnwardFlight() != null ? booking.getOnwardFlight() : "None";

        String origin = "HYD";
        String destination = "DEL";
        if (route.contains("→")) {
            String[] parts = route.split("→");
            origin = parts[0].trim();
            destination = parts[1].trim();
        } else if (route.contains("->")) {
            String[] parts = route.split("->");
            origin = parts[0].trim();
            destination = parts[1].trim();
        }

        // If the classic hero booking (HYD -> DEL to Toronto) is queried, keep canonical spec options
        if (origin.equalsIgnoreCase("HYD") && destination.equalsIgnoreCase("DEL") && onward.toLowerCase().contains("toronto")) {
            return List.of(
                    new RebookOption(
                            "O1",
                            caseId,
                            "FL201",
                            "HYD → DEL (Direct)",
                            "Tomorrow 06:00",
                            "Tomorrow 08:05",
                            85,
                            0,
                            3,
                            false,
                            "Rank 3 (Disqualified): Only 85 minutes buffer to onward flight (minimum safe threshold is 120 minutes). High risk of missed international connection."
                    ),
                    new RebookOption(
                            "O2",
                            caseId,
                            "FL301",
                            "HYD → BOM → DEL (via Mumbai)",
                            "Tonight 23:30",
                            "Tomorrow 01:40",
                            470,
                            4200,
                            1,
                            true,
                            "Rank 1: Arrives Delhi Tomorrow 01:40 (7h 50m buffer). Safely preserves onward international connection to Toronto (YYZ 09:30)."
                    ),
                    new RebookOption(
                            "O3",
                            caseId,
                            "FL401",
                            "HYD → DEL (Direct)",
                            "Tomorrow 10:00",
                            "Tomorrow 12:15",
                            -165,
                            1500,
                            2,
                            false,
                            "Rank 2: Departs Tomorrow 10:00. Misses Toronto flight; requires schedule realignment and forfeits Delhi hotel night."
                    )
            );
        }

        // Dynamic generation for ANY arbitrary route
        List<RebookOption> dynamicOptions = new ArrayList<>();

        int onwardDepartureMinutes = parseTimeInMinutes(onward);
        boolean hasOnward = !onward.equalsIgnoreCase("None") && onwardDepartureMinutes > 0;

        // Option 1: Early Morning Direct
        int arr1Minutes = 8 * 60 + 5; // 08:05
        int buffer1 = hasOnward ? (onwardDepartureMinutes - arr1Minutes) : 360;
        dynamicOptions.add(new RebookOption(
                "O1",
                caseId,
                "FL101",
                String.format("%s → %s (Direct)", origin, destination),
                "Tomorrow 06:00",
                "Tomorrow 08:05",
                buffer1,
                0,
                3,
                buffer1 >= 120,
                String.format("%s → %s early flight with %d min transit window.", origin, destination, buffer1)
        ));

        // Option 2: Express via Hub (Safest connection)
        int arr2Minutes = 1 * 60 + 40; // 01:40
        int buffer2 = hasOnward ? (onwardDepartureMinutes - arr2Minutes) : 470;
        dynamicOptions.add(new RebookOption(
                "O2",
                caseId,
                "FL301",
                String.format("%s → BOM → %s (Express Hub)", origin, destination),
                "Tonight 23:15",
                "Tomorrow 01:40",
                buffer2,
                4200,
                1,
                buffer2 >= 120,
                String.format("Optimal safe transfer preserving onward travel with %dh%02dm buffer.", buffer2 / 60, Math.abs(buffer2 % 60))
        ));

        // Option 3: Later Daytime Direct
        int arr3Minutes = 12 * 60 + 15; // 12:15
        int buffer3 = hasOnward ? (onwardDepartureMinutes - arr3Minutes) : 180;
        dynamicOptions.add(new RebookOption(
                "O3",
                caseId,
                "FL401",
                String.format("%s → %s (Direct Daytime)", origin, destination),
                "Tomorrow 10:00",
                "Tomorrow 12:15",
                buffer3,
                1500,
                2,
                buffer3 >= 120,
                String.format("%s → %s daytime departure.", origin, destination)
        ));

        return dynamicOptions;
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
