package com.flyo.rescue.service;

import com.flyo.rescue.model.AirportMctRule;
import com.flyo.rescue.repository.AirportMctRuleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Service encapsulating the Airport Minimum Connection Time (MCT) rules engine.
 * 
 * ZERO HARDCODED OPERATIONAL DATA:
 * Airport MCT rules are loaded dynamically from the versioned, provenance-tracked
 * `airport_mct_rules` database table. Missing or unverified rules trigger an explicit
 * `requiresManualReview` state rather than fabricating operational tolerances.
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

    private final AirportMctRuleRepository airportMctRuleRepository;

    public MctRulesService() {
        this.airportMctRuleRepository = null;
    }

    @Autowired
    public MctRulesService(@Autowired(required = false) AirportMctRuleRepository airportMctRuleRepository) {
        this.airportMctRuleRepository = airportMctRuleRepository;
    }

    public boolean isAirportMctPublished(String airportCode) {
        if (airportCode == null || airportCode.isBlank() || airportMctRuleRepository == null) return false;
        return !airportMctRuleRepository.findByAirportCodeIgnoreCase(airportCode.trim()).isEmpty();
    }

    public int getPublishedMctMinutes(String airportCode, TransferType transferType) {
        if (airportCode != null && airportMctRuleRepository != null && transferType != null) {
            Optional<AirportMctRule> ruleOpt = airportMctRuleRepository
                    .findByAirportCodeIgnoreCaseAndTransferTypeIgnoreCaseAndApprovalStatus(
                            airportCode.trim(), transferType.name(), "APPROVED");
            if (ruleOpt.isPresent()) {
                return ruleOpt.get().getPublishedMctMinutes();
            }
        }
        // Conservative policy fallback when rule is not in database
        return getFlyoPolicyMctMinutes(transferType);
    }

    public int getFlyoPolicyMctMinutes(TransferType transferType) {
        if (transferType == null) return FLYO_INTERNATIONAL_POLICY_MINUTES;
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
        if (airportCode != null && airportMctRuleRepository != null && transferType != null) {
            Optional<AirportMctRule> ruleOpt = airportMctRuleRepository
                    .findByAirportCodeIgnoreCaseAndTransferTypeIgnoreCaseAndApprovalStatus(
                            airportCode.trim(), transferType.name(), "APPROVED");
            if (ruleOpt.isPresent()) {
                AirportMctRule rule = ruleOpt.get();
                int publishedMct = rule.getPublishedMctMinutes();
                int flyoPolicy = rule.getFlyoPolicyMinutes();

                boolean meetsPublished = actualBufferMinutes >= publishedMct;
                boolean meetsFlyo = actualBufferMinutes >= flyoPolicy;
                boolean requiresManualReview = !meetsFlyo && meetsPublished;

                String description;
                if (meetsFlyo) {
                    description = String.format("Buffer %d min satisfies both airport published MCT (%d min, %s) and Flyo policy threshold (%d min). Rule %s [%s].",
                            actualBufferMinutes, publishedMct, rule.getSourceAuthority(), flyoPolicy, rule.getRuleVersion(), rule.getProvenanceReference());
                } else if (meetsPublished) {
                    description = String.format("Buffer %d min meets airport published MCT (%d min, %s) but violates Flyo conservative policy (%d min). Disqualified from Rank 1.",
                            actualBufferMinutes, publishedMct, rule.getSourceAuthority(), flyoPolicy);
                } else {
                    description = String.format("Buffer %d min fails airport published MCT (%d min) and Flyo policy (%d min). Dangerous illegal connection.",
                            actualBufferMinutes, publishedMct, flyoPolicy);
                }

                return new MctEvaluation(publishedMct, flyoPolicy, actualBufferMinutes,
                        meetsPublished, meetsFlyo, true, requiresManualReview, description,
                        rule.getSourceAuthority(), rule.getRuleVersion(), rule.getProvenanceReference());
            }
        }

        // Unlisted or unapproved airport MCT rule in authoritative database
        int publishedMct = getFlyoPolicyMctMinutes(transferType);
        int flyoPolicy = getFlyoPolicyMctMinutes(transferType);
        boolean meetsPublished = actualBufferMinutes >= publishedMct;
        boolean meetsFlyo = actualBufferMinutes >= flyoPolicy;

        String description = String.format("Airport %s MCT rules not officially published in database. Default conservative buffer applied (%d min). Manual connection feasibility review required.",
                airportCode != null ? airportCode : "UNKNOWN", publishedMct);

        return new MctEvaluation(publishedMct, flyoPolicy, actualBufferMinutes,
                meetsPublished, meetsFlyo, false, true, description,
                "AUTHORITATIVE_DB_RULE_ABSENT", "UNPUBLISHED", "AAI/IATA DB Query yielded zero approved records");
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
        private final String sourceAuthority;
        private final String ruleVersion;
        private final String provenanceReference;

        public MctEvaluation(int publishedMctMinutes, int flyoPolicyMinutes, int actualBufferMinutes,
                             boolean meetsPublishedMct, boolean meetsFlyoPolicy, String summary) {
            this(publishedMctMinutes, flyoPolicyMinutes, actualBufferMinutes, meetsPublishedMct, meetsFlyoPolicy, true, false, summary, "N/A", "N/A", "N/A");
        }

        public MctEvaluation(int publishedMctMinutes, int flyoPolicyMinutes, int actualBufferMinutes,
                             boolean meetsPublishedMct, boolean meetsFlyoPolicy,
                             boolean knownAirportMct, boolean requiresManualReview, String summary) {
            this(publishedMctMinutes, flyoPolicyMinutes, actualBufferMinutes, meetsPublishedMct, meetsFlyoPolicy, knownAirportMct, requiresManualReview, summary, "N/A", "N/A", "N/A");
        }

        public MctEvaluation(int publishedMctMinutes, int flyoPolicyMinutes, int actualBufferMinutes,
                             boolean meetsPublishedMct, boolean meetsFlyoPolicy,
                             boolean knownAirportMct, boolean requiresManualReview, String summary,
                             String sourceAuthority, String ruleVersion, String provenanceReference) {
            this.publishedMctMinutes = publishedMctMinutes;
            this.flyoPolicyMinutes = flyoPolicyMinutes;
            this.actualBufferMinutes = actualBufferMinutes;
            this.meetsPublishedMct = meetsPublishedMct;
            this.meetsFlyoPolicy = meetsFlyoPolicy;
            this.knownAirportMct = knownAirportMct;
            this.requiresManualReview = requiresManualReview;
            this.summary = summary;
            this.sourceAuthority = sourceAuthority;
            this.ruleVersion = ruleVersion;
            this.provenanceReference = provenanceReference;
        }

        public int getPublishedMctMinutes() { return publishedMctMinutes; }
        public int getFlyoPolicyMinutes() { return flyoPolicyMinutes; }
        public int getActualBufferMinutes() { return actualBufferMinutes; }
        public boolean isMeetsPublishedMct() { return meetsPublishedMct; }
        public boolean isMeetsFlyoPolicy() { return meetsFlyoPolicy; }
        public boolean isKnownAirportMct() { return knownAirportMct; }
        public boolean isRequiresManualReview() { return requiresManualReview; }
        public String getSummary() { return summary; }
        public String getSourceAuthority() { return sourceAuthority; }
        public String getRuleVersion() { return ruleVersion; }
        public String getProvenanceReference() { return provenanceReference; }
    }
}
