package com.flyo.rescue.integration.reconciliation;

import com.flyo.rescue.integration.dto.PnrRecord;
import com.flyo.rescue.integration.dto.TicketVerificationResult;
import com.flyo.rescue.model.DiscrepancyRecord;
import com.flyo.rescue.model.RescueCase;
import com.flyo.rescue.repository.DiscrepancyRecordRepository;
import com.flyo.rescue.repository.RescueCaseRepository;
import com.flyo.rescue.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Enterprise External-State Reconciliation Service.
 * Periodically audits local Flyo case states against authoritative external sources
 * (Airline PSS Host, GDS reservation records, and Payment Gateways).
 * Identifies phantom bookings, broken ticket links, and unauthorized external cancellations.
 */
@Service
public class ExternalStateReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ExternalStateReconciliationService.class);

    private final RescueCaseRepository rescueCaseRepository;
    private final DiscrepancyRecordRepository discrepancyRecordRepository;
    private final AuditService auditService;

    public ExternalStateReconciliationService(RescueCaseRepository rescueCaseRepository,
                                             DiscrepancyRecordRepository discrepancyRecordRepository,
                                             AuditService auditService) {
        this.rescueCaseRepository = rescueCaseRepository;
        this.discrepancyRecordRepository = discrepancyRecordRepository;
        this.auditService = auditService;
    }

    @Transactional
    public List<DiscrepancyRecord> reconcileCaseState(String caseId, String remotePnrStatus, String remoteCouponStatus) {
        RescueCase rescueCase = rescueCaseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        List<DiscrepancyRecord> discrepancies = new ArrayList<>();

        // 1. Check for Completed Case Drift (Local DONE but Remote Void / Cancelled)
        if ("DONE".equals(rescueCase.getState())) {
            if ("CANCELLED".equalsIgnoreCase(remotePnrStatus) || "VOID".equalsIgnoreCase(remoteCouponStatus)) {
                DiscrepancyRecord disc = new DiscrepancyRecord(
                        "DISC-" + UUID.randomUUID().toString().substring(0, 8),
                        caseId,
                        rescueCase.getTenantId(),
                        "REMOTE_PNR_VOID_OR_CANCELLED",
                        rescueCase.getState(),
                        remotePnrStatus + " / " + remoteCouponStatus,
                        "Authoritative airline host shows PNR cancelled or ticket voided after local rescue completion."
                );
                discrepancyRecordRepository.save(disc);
                discrepancies.add(disc);

                auditService.logEvent(
                        caseId,
                        "RECONCILIATION_DISCREPANCY_DETECTED",
                        "External Reconciliation Engine",
                        "State divergence: Local=DONE, Remote=" + remotePnrStatus + ". Ticket coupon: " + remoteCouponStatus
                );
            }
        }

        // 2. Check for Stuck In-Flight Saga Cases
        if ("REBOOKING".equals(rescueCase.getState()) && "CONFIRMED".equalsIgnoreCase(remotePnrStatus)) {
            DiscrepancyRecord disc = new DiscrepancyRecord(
                    "DISC-" + UUID.randomUUID().toString().substring(0, 8),
                    caseId,
                    rescueCase.getTenantId(),
                    "LOCAL_REBOOKING_REMOTE_CONFIRMED",
                    rescueCase.getState(),
                    remotePnrStatus,
                    "Airline host has already confirmed new reservation, but local case remains in REBOOKING."
            );
            discrepancyRecordRepository.save(disc);
            discrepancies.add(disc);
        }

        return discrepancies;
    }

    @Transactional
    public void resolveDiscrepancy(String discrepancyId, String resolutionNotes) {
        discrepancyRecordRepository.findById(discrepancyId).ifPresent(d -> {
            d.setResolved(true);
            d.setResolvedAt(LocalDateTime.now());
            d.setDetails(d.getDetails() + " [RESOLVED: " + resolutionNotes + "]");
            discrepancyRecordRepository.save(d);
            log.info("[Reconciliation] Discrepancy '{}' resolved for case '{}'", discrepancyId, d.getCaseId());
        });
    }

    public List<DiscrepancyRecord> getUnresolvedDiscrepancies(String tenantId) {
        return discrepancyRecordRepository.findByTenantIdAndResolvedOrderByCreatedAtDesc(tenantId, false);
    }
}
