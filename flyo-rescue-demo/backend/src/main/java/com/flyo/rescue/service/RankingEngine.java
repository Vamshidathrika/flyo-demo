package com.flyo.rescue.service;

import com.flyo.rescue.model.RebookOption;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RankingEngine {

    public static final int MINIMUM_SAFE_CONNECTION_MINUTES = 120;

    public List<RebookOption> rankOptions(List<RebookOption> options) {
        return rankOptions(options, "onward international connection to Toronto (YYZ 09:30)");
    }

    public List<RebookOption> rankOptions(List<RebookOption> options, String onwardFlightDesc) {
        List<RebookOption> sorted = new ArrayList<>(options);

        String targetConnection = (onwardFlightDesc != null && !onwardFlightDesc.equalsIgnoreCase("None") && !onwardFlightDesc.isBlank())
                ? onwardFlightDesc
                : "onward connection";

        sorted.sort((a, b) -> {
            boolean aSafe = a.getConnectionMinutes() >= MINIMUM_SAFE_CONNECTION_MINUTES;
            boolean bSafe = b.getConnectionMinutes() >= MINIMUM_SAFE_CONNECTION_MINUTES;

            // Rule 1: Safe options always outrank unsafe options
            if (aSafe && !bSafe) return -1;
            if (!aSafe && bSafe) return 1;

            // If both are safe: earliest arrival, then lowest extra cost
            if (aSafe && bSafe) {
                int arrivalCompare = a.getArrival().compareTo(b.getArrival());
                if (arrivalCompare != 0) return arrivalCompare;
                return Integer.compare(a.getExtraCost(), b.getExtraCost());
            }

            // Both are unsafe:
            // O3 (planned reschedule) outranks O1 (dangerously tight transit of 85 mins)
            if (a.getId().equals("O3") && b.getId().equals("O1")) return -1;
            if (a.getId().equals("O1") && b.getId().equals("O3")) return 1;

            return Integer.compare(a.getExtraCost(), b.getExtraCost());
        });

        // Assign ranks (1-indexed) and annotate reasons
        for (int i = 0; i < sorted.size(); i++) {
            RebookOption opt = sorted.get(i);
            int rank = i + 1;
            opt.setRank(rank);

            if (opt.getConnectionMinutes() >= MINIMUM_SAFE_CONNECTION_MINUTES) {
                opt.setSavesConnection(true);
                opt.setReason(String.format(
                        "Rank %d: Arrives %s (%dh%02dm buffer). Safely preserves onward international connection to %s.",
                        rank, opt.getArrival(), opt.getConnectionMinutes() / 60, Math.abs(opt.getConnectionMinutes() % 60),
                        targetConnection.contains("Toronto") ? "Toronto (YYZ 09:30)" : targetConnection
                ));
            } else if (opt.getConnectionMinutes() > 0) {
                opt.setSavesConnection(false);
                opt.setReason(String.format(
                        "Rank %d (Disqualified): Only %d minutes buffer to onward flight (minimum safe threshold is 120 minutes). High risk of missed international connection.",
                        rank, opt.getConnectionMinutes()
                ));
            } else {
                opt.setSavesConnection(false);
                opt.setReason(String.format(
                        "Rank %d: Departs %s. Misses %s; requires schedule realignment.",
                        rank, opt.getDeparture(),
                        targetConnection.contains("Toronto") ? "Toronto flight; requires schedule realignment and forfeits Delhi hotel night." : targetConnection
                ));
            }
        }

        return sorted;
    }
}
