package com.flyo.rescue.service;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Service encapsulating the published Airport Minimum Connection Time (MCT) rules matrix
 * alongside Flyo's conservative passenger protection policy.
 *
 * Distinguishes published airport MCT (e.g., Delhi T3 D-I transfer time of 90 minutes)
 * from Flyo's operational policy (120 minutes required to safely protect onward international connections).
 */
@Service
public class MctRulesService {

    public enum TransferType {
        DOMESTIC_TO_DOMESTIC,
        DOMESTIC_TO_INTERNATIONAL,
        INTERNATIONAL_TO_DOMESTIC,
        INTERNATIONAL_TO_INTERNATIONAL,
        INTER_TERMINAL
    }

    public static final int FLYO_DOMESTIC_POLICY_MINUTES = 75;
    public static final int FLYO_INTERNATIONAL_POLICY_MINUTES = 120;
    public static final int FLYO_INTER_TERMINAL_POLICY_MINUTES = 150;

    // Airport code -> (TransferType -> Published MCT in minutes)
    private static final Map<String, Map<TransferType, Integer>> PUBLISHED_MCT_MATRIX;

    static {
        Map<String, Map<TransferType, Integer>> matrix = new HashMap<>();

        // Delhi (DEL - Indira Gandhi International)
        Map<TransferType, Integer> del = new HashMap<>();
        del.put(TransferType.DOMESTIC_TO_DOMESTIC, 60);
        del.put(TransferType.DOMESTIC_TO_INTERNATIONAL, 90);
        del.put(TransferType.INTERNATIONAL_TO_DOMESTIC, 120);
        del.put(TransferType.INTERNATIONAL_TO_INTERNATIONAL, 90);
        del.put(TransferType.INTER_TERMINAL, 120);
        matrix.put("DEL", Collections.unmodifiableMap(del));

        // Mumbai (BOM - Chhatrapati Shivaji Maharaj International)
        Map<TransferType, Integer> bom = new HashMap<>();
        bom.put(TransferType.DOMESTIC_TO_DOMESTIC, 60);
        bom.put(TransferType.DOMESTIC_TO_INTERNATIONAL, 90);
        bom.put(TransferType.INTERNATIONAL_TO_DOMESTIC, 120);
        bom.put(TransferType.INTERNATIONAL_TO_INTERNATIONAL, 90);
        bom.put(TransferType.INTER_TERMINAL, 120);
        matrix.put("BOM", Collections.unmodifiableMap(bom));

        // Bengaluru (BLR - Kempegowda International)
        Map<TransferType, Integer> blr = new HashMap<>();
        blr.put(TransferType.DOMESTIC_TO_DOMESTIC, 45);
        blr.put(TransferType.DOMESTIC_TO_INTERNATIONAL, 75);
        blr.put(TransferType.INTERNATIONAL_TO_DOMESTIC, 90);
        blr.put(TransferType.INTERNATIONAL_TO_INTERNATIONAL, 75);
        blr.put(TransferType.INTER_TERMINAL, 90);
        matrix.put("BLR", Collections.unmodifiableMap(blr));

        // Hyderabad (HYD - Rajiv Gandhi International)
        Map<TransferType, Integer> hyd = new HashMap<>();
        hyd.put(TransferType.DOMESTIC_TO_DOMESTIC, 45);
        hyd.put(TransferType.DOMESTIC_TO_INTERNATIONAL, 75);
        hyd.put(TransferType.INTERNATIONAL_TO_DOMESTIC, 90);
        hyd.put(TransferType.INTERNATIONAL_TO_INTERNATIONAL, 75);
        hyd.put(TransferType.INTER_TERMINAL, 90);
        matrix.put("HYD", Collections.unmodifiableMap(hyd));

        // London Heathrow (LHR)
        Map<TransferType, Integer> lhr = new HashMap<>();
        lhr.put(TransferType.DOMESTIC_TO_DOMESTIC, 60);
        lhr.put(TransferType.DOMESTIC_TO_INTERNATIONAL, 75);
        lhr.put(TransferType.INTERNATIONAL_TO_INTERNATIONAL, 90);
        lhr.put(TransferType.INTER_TERMINAL, 105);
        matrix.put("LHR", Collections.unmodifiableMap(lhr));

        // Frankfurt (FRA)
        Map<TransferType, Integer> fra = new HashMap<>();
        fra.put(TransferType.DOMESTIC_TO_DOMESTIC, 45);
        fra.put(TransferType.DOMESTIC_TO_INTERNATIONAL, 60);
        fra.put(TransferType.INTERNATIONAL_TO_INTERNATIONAL, 60);
        fra.put(TransferType.INTER_TERMINAL, 90);
        matrix.put("FRA", Collections.unmodifiableMap(fra));

        PUBLISHED_MCT_MATRIX = Collections.unmodifiableMap(matrix);
    }

    public boolean isAirportMctPublished(String airportCode) {
        if (airportCode == null || airportCode.isBlank()) return false;
        return PUBLISHED_MCT_MATRIX.containsKey(airportCode.toUpperCase().trim());
    }

    public int getPublishedMctMinutes(String airportCode, TransferType transferType) {
        if (airportCode != null) {
            Map<TransferType, Integer> airportRules = PUBLISHED_MCT_MATRIX.get(airportCode.toUpperCase().trim());
            if (airportRules != null && airportRules.containsKey(transferType)) {
                return airportRules.get(transferType);
            }
        }
        // Conservative published fallback for unlisted airports
        switch (transferType) {
            case DOMESTIC_TO_DOMESTIC: return 75;
            case DOMESTIC_TO_INTERNATIONAL: return 120;
            case INTERNATIONAL_TO_DOMESTIC: return 150;
            case INTERNATIONAL_TO_INTERNATIONAL: return 120;
            case INTER_TERMINAL: return 150;
            default: return 120;
        }
    }

    public int getFlyoPolicyMctMinutes(TransferType transferType) {
        switch (transferType) {
            case DOMESTIC_TO_DOMESTIC: return FLYO_DOMESTIC_POLICY_MINUTES;
            case DOMESTIC_TO_INTERNATIONAL:
            case INTERNATIONAL_TO_INTERNATIONAL:
            case INTERNATIONAL_TO_DOMESTIC:
                return FLYO_INTERNATIONAL_POLICY_MINUTES;
            case INTER_TERMINAL:
                return FLYO_INTER_TERMINAL_POLICY_MINUTES;
            default:
                return FLYO_INTERNATIONAL_POLICY_MINUTES;
        }
    }

    public MctEvaluation evaluate(String airportCode, TransferType transferType, int actualBufferMinutes) {
        boolean known = isAirportMctPublished(airportCode);
        int publishedMct = getPublishedMctMinutes(airportCode, transferType);
        int flyoPolicy = getFlyoPolicyMctMinutes(transferType);

        boolean meetsPublished = actualBufferMinutes >= publishedMct;
        boolean meetsFlyo = actualBufferMinutes >= flyoPolicy;
        boolean requiresManualReview = !known || (!meetsFlyo && meetsPublished);

        String description;
        if (!known) {
            description = String.format("Airport %s MCT rules not officially published in matrix. Default conservative buffer applied (%d min). Manual connection feasibility review required.",
                    airportCode, publishedMct);
        } else if (meetsFlyo) {
            description = String.format("Buffer %d min satisfies both airport published MCT (%d min) and Flyo policy threshold (%d min).",
                    actualBufferMinutes, publishedMct, flyoPolicy);
        } else if (meetsPublished) {
            description = String.format("Buffer %d min meets airport published MCT (%d min) but violates Flyo conservative policy (%d min). Disqualified from Rank 1.",
                    actualBufferMinutes, publishedMct, flyoPolicy);
        } else {
            description = String.format("Buffer %d min fails airport published MCT (%d min) and Flyo policy (%d min). Dangerous illegal connection.",
                    actualBufferMinutes, publishedMct, flyoPolicy);
        }

        return new MctEvaluation(publishedMct, flyoPolicy, actualBufferMinutes, meetsPublished, meetsFlyo, known, requiresManualReview, description);
    }

    public static class MctEvaluation {
        private final int publishedMctMinutes;
        private final int flyoPolicyMinutes;
        private final int actualBufferMinutes;
        private final boolean meetsPublishedMct;
        private final boolean meetsFlyoPolicy;
        private final boolean knownAirportMct;
        private final boolean requiresManualReview;
        private final String summary;

        public MctEvaluation(int publishedMctMinutes, int flyoPolicyMinutes, int actualBufferMinutes,
                             boolean meetsPublishedMct, boolean meetsFlyoPolicy, String summary) {
            this(publishedMctMinutes, flyoPolicyMinutes, actualBufferMinutes, meetsPublishedMct, meetsFlyoPolicy, true, false, summary);
        }

        public MctEvaluation(int publishedMctMinutes, int flyoPolicyMinutes, int actualBufferMinutes,
                             boolean meetsPublishedMct, boolean meetsFlyoPolicy,
                             boolean knownAirportMct, boolean requiresManualReview, String summary) {
            this.publishedMctMinutes = publishedMctMinutes;
            this.flyoPolicyMinutes = flyoPolicyMinutes;
            this.actualBufferMinutes = actualBufferMinutes;
            this.meetsPublishedMct = meetsPublishedMct;
            this.meetsFlyoPolicy = meetsFlyoPolicy;
            this.knownAirportMct = knownAirportMct;
            this.requiresManualReview = requiresManualReview;
            this.summary = summary;
        }

        public int getPublishedMctMinutes() { return publishedMctMinutes; }
        public int getFlyoPolicyMinutes() { return flyoPolicyMinutes; }
        public int getActualBufferMinutes() { return actualBufferMinutes; }
        public boolean isMeetsPublishedMct() { return meetsPublishedMct; }
        public boolean isMeetsFlyoPolicy() { return meetsFlyoPolicy; }
        public boolean isKnownAirportMct() { return knownAirportMct; }
        public boolean isRequiresManualReview() { return requiresManualReview; }
        public String getSummary() { return summary; }
    }
}
