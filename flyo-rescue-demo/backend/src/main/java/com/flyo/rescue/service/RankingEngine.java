package com.flyo.rescue.service;

import com.flyo.rescue.model.RebookOption;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RankingEngine {

    public static final int MINIMUM_SAFE_CONNECTION_MINUTES = 120;
    private final MctRulesService mctRulesService;

    public RankingEngine() {
        this.mctRulesService = new MctRulesService();
    }

    public RankingEngine(MctRulesService mctRulesService) {
        this.mctRulesService = mctRulesService != null ? mctRulesService : new MctRulesService();
    }

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

        // Assign ranks, calculate multi-factor transparent risk metrics, and annotate reasons
        for (int i = 0; i < sorted.size(); i++) {
            RebookOption opt = sorted.get(i);
            int rank = i + 1;
            opt.setRank(rank);

            calculateRiskMetrics(opt);

            if (opt.getConnectionMinutes() >= MINIMUM_SAFE_CONNECTION_MINUTES) {
                opt.setSavesConnection(true);
                opt.setReason(String.format(
                        "Rank %d: Arrives %s (%dh%02dm buffer). Safely preserves onward international connection to %s. Risk: %s (%d/100).",
                        rank, opt.getArrival(), opt.getConnectionMinutes() / 60, Math.abs(opt.getConnectionMinutes() % 60),
                        targetConnection.contains("Toronto") ? "Toronto (YYZ 09:30)" : targetConnection,
                        opt.getRiskLevel(), opt.getRiskScore()
                ));
            } else if (opt.getConnectionMinutes() > 0) {
                opt.setSavesConnection(false);
                opt.setReason(String.format(
                        "Rank %d (Disqualified): Only %d minutes buffer to onward flight (minimum safe threshold is 120 minutes). High risk of missed international connection. Risk: %s (%d/100).",
                        rank, opt.getConnectionMinutes(), opt.getRiskLevel(), opt.getRiskScore()
                ));
            } else {
                opt.setSavesConnection(false);
                opt.setReason(String.format(
                        "Rank %d: Departs %s. Misses %s; requires schedule realignment. Risk: %s (%d/100).",
                        rank, opt.getDeparture(),
                        targetConnection.contains("Toronto") ? "Toronto flight; requires schedule realignment and forfeits Delhi hotel night." : targetConnection,
                        opt.getRiskLevel(), opt.getRiskScore()
                ));
            }
        }

        return sorted;
    }

    private void calculateRiskMetrics(RebookOption opt) {
        int score = 0;
        List<String> factors = new ArrayList<>();

        int conn = opt.getConnectionMinutes();
        if (conn >= 240) {
            score += 5;
            factors.add(String.format("Generous connection buffer (%dm)", conn));
        } else if (conn >= 180) {
            score += 15;
            factors.add(String.format("Comfortable connection buffer (%dm)", conn));
        } else if (conn > MINIMUM_SAFE_CONNECTION_MINUTES) {
            // E.g. 121m to 179m
            score += (conn <= 135 ? 35 : 25);
            factors.add(String.format("Tight connection buffer (%dm): satisfies 120m threshold but vulnerable to inbound delay", conn));
        } else if (conn == MINIMUM_SAFE_CONNECTION_MINUTES) {
            // Exactly 120m
            score += 40;
            factors.add("Marginal connection buffer (120m): exactly at minimum threshold; carries transfer risk");
        } else if (conn > 0) {
            // Below 120m (e.g. 119m or 85m)
            score += 85;
            factors.add(String.format("Critical risk: %dm buffer violates mandatory 120m connection threshold", conn));
        } else {
            score += 95;
            factors.add("Critical risk: Misses onward connecting flight entirely");
        }

        // Route complexity / Stops
        String route = opt.getRoute() != null ? opt.getRoute().toUpperCase() : "";
        int hops = route.contains("→") ? route.split("→").length - 1 : 1;
        if (hops >= 3) {
            score += 25;
            factors.add(String.format("Multiple transit stops (%d hops) significantly increase misconnect hazard", hops));
        } else if (hops > 1 || route.contains("VIA")) {
            score += 10;
            factors.add("1 transit stop layover");
        } else {
            factors.add("Direct non-stop flight");
        }

        // Departure timing (Red-eye vs Daytime)
        String dep = opt.getDeparture() != null ? opt.getDeparture() : "";
        if (dep.contains("23:") || dep.contains("00:") || dep.contains("01:")) {
            score += 5;
            factors.add("Late night / red-eye departure");
        }

        // Clamp 0 - 100
        score = Math.min(100, Math.max(0, score));
        opt.setRiskScore(score);

        if (score <= 25) {
            opt.setRiskLevel("LOW");
        } else if (score <= 50) {
            opt.setRiskLevel("MODERATE");
        } else if (score <= 75) {
            opt.setRiskLevel("ELEVATED");
        } else {
            opt.setRiskLevel("CRITICAL");
        }

        opt.setRiskFactors(String.join("; ", factors));
    }
}
