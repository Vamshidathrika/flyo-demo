package com.flyo.rescue.controller;

import com.flyo.rescue.model.OutboxEvent;
import com.flyo.rescue.model.SagaExecution;
import com.flyo.rescue.model.SagaStepLog;
import com.flyo.rescue.repository.OutboxEventRepository;
import com.flyo.rescue.repository.SagaExecutionRepository;
import com.flyo.rescue.security.FlyoUserPrincipal;
import com.flyo.rescue.security.TenantContext;
import com.flyo.rescue.service.OutboxService;
import com.flyo.rescue.service.saga.RebookingSagaCoordinator;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Administrative Observability & Failure Recovery Controller.
 *
 * Exposes monitoring telemetry, dead-letter inspection, and safe manual recovery actions
 * for the Transactional Outbox and Distributed Saga subsystems.
 * Enforces strict multi-tenant boundary checks: tenant operators can only inspect and retry
 * events and sagas originating from their authorized tenant.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
public class SagaMonitoringController {

    private final OutboxService outboxService;
    private final OutboxEventRepository outboxEventRepository;
    private final RebookingSagaCoordinator sagaCoordinator;
    private final SagaExecutionRepository sagaExecutionRepository;

    public SagaMonitoringController(
            OutboxService outboxService,
            OutboxEventRepository outboxEventRepository,
            RebookingSagaCoordinator sagaCoordinator,
            SagaExecutionRepository sagaExecutionRepository) {
        this.outboxService = outboxService;
        this.outboxEventRepository = outboxEventRepository;
        this.sagaCoordinator = sagaCoordinator;
        this.sagaExecutionRepository = sagaExecutionRepository;
    }

    private boolean isGlobalAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof FlyoUserPrincipal p) {
            return p.getAuthorizedTenants().contains("*");
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // OUTBOX OBSERVABILITY & RECOVERY
    // -------------------------------------------------------------------------

    @GetMapping("/outbox/metrics")
    public ResponseEntity<Map<String, Object>> getOutboxMetrics() {
        return ResponseEntity.ok(outboxService.getOutboxMetrics());
    }

    @GetMapping("/outbox/events")
    public ResponseEntity<List<OutboxEvent>> getOutboxEvents(
            @RequestParam(required = false) String status) {
        String tenantId = TenantContext.getTenantId();
        if (isGlobalAdmin()) {
            if (status != null && !status.isBlank()) {
                return ResponseEntity.ok(outboxEventRepository.findByStatusOrderByCreatedAtAsc(status.toUpperCase()));
            }
            return ResponseEntity.ok(outboxEventRepository.findAll());
        }
        if (status != null && !status.isBlank()) {
            return ResponseEntity.ok(outboxEventRepository.findByTenantIdAndStatusOrderByCreatedAtAsc(tenantId, status.toUpperCase()));
        }
        return ResponseEntity.ok(outboxEventRepository.findByTenantId(tenantId));
    }

    @PostMapping("/outbox/events/{eventId}/retry")
    public ResponseEntity<Map<String, Object>> retryOutboxEvent(@PathVariable String eventId) {
        String tenantId = TenantContext.getTenantId();
        Optional<OutboxEvent> evtOpt = outboxEventRepository.findById(eventId);
        if (evtOpt.isEmpty() || (!isGlobalAdmin() && !tenantId.equals(evtOpt.get().getTenantId()))) {
            return ResponseEntity.notFound().build();
        }

        boolean success = outboxService.retryDeadLetter(eventId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("eventId", eventId);
        resp.put("retried", success);
        resp.put("message", success ? "Event reset to PENDING for immediate redispatch." : "Event not in DEAD_LETTER status or not found.");
        return ResponseEntity.ok(resp);
    }

    // -------------------------------------------------------------------------
    // SAGA OBSERVABILITY & RECOVERY
    // -------------------------------------------------------------------------

    @GetMapping("/sagas/metrics")
    public ResponseEntity<Map<String, Object>> getSagaMetrics() {
        return ResponseEntity.ok(sagaCoordinator.getSagaMetrics());
    }

    @GetMapping("/sagas")
    public ResponseEntity<List<SagaExecution>> getSagas(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String caseId) {
        String tenantId = TenantContext.getTenantId();
        if (isGlobalAdmin()) {
            if (caseId != null && !caseId.isBlank()) {
                return ResponseEntity.ok(sagaExecutionRepository.findByCaseIdOrderByCreatedAtDesc(caseId));
            }
            if (status != null && !status.isBlank()) {
                return ResponseEntity.ok(sagaExecutionRepository.findByStatus(status.toUpperCase()));
            }
            return ResponseEntity.ok(sagaExecutionRepository.findAll());
        }

        if (caseId != null && !caseId.isBlank()) {
            return ResponseEntity.ok(sagaExecutionRepository.findByTenantIdAndCaseIdOrderByCreatedAtDesc(tenantId, caseId));
        }
        if (status != null && !status.isBlank()) {
            return ResponseEntity.ok(sagaExecutionRepository.findByTenantIdAndStatus(tenantId, status.toUpperCase()));
        }
        return ResponseEntity.ok(sagaExecutionRepository.findByTenantId(tenantId));
    }

    @GetMapping("/sagas/{sagaId}")
    public ResponseEntity<Map<String, Object>> getSagaDetails(@PathVariable String sagaId) {
        String tenantId = TenantContext.getTenantId();
        return sagaCoordinator.getSagaById(sagaId)
                .filter(saga -> isGlobalAdmin() || tenantId.equals(saga.getTenantId()))
                .map(saga -> {
                    List<SagaStepLog> logs = sagaCoordinator.getSagaLogs(sagaId);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("saga", saga);
                    body.put("stepLogs", logs);
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sagas/{sagaId}/retry")
    public ResponseEntity<Map<String, Object>> retrySaga(@PathVariable String sagaId) {
        String tenantId = TenantContext.getTenantId();
        return sagaCoordinator.getSagaById(sagaId)
                .filter(saga -> isGlobalAdmin() || tenantId.equals(saga.getTenantId()))
                .map(saga -> {
                    saga.setStatus(SagaExecution.STATUS_PENDING);
                    saga.setNextRetryAt(LocalDateTime.now());
                    saga.setAttemptCount(0);
                    saga.setLockedBy(null);
                    saga.setLockedAt(null);
                    sagaExecutionRepository.save(saga);

                    // Trigger execution step
                    sagaCoordinator.executeSagaStep(sagaId, "admin-operator");

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("sagaId", sagaId);
                    resp.put("status", saga.getStatus());
                    resp.put("message", "Saga reset to PENDING and execution triggered.");
                    return ResponseEntity.ok(resp);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sagas/{sagaId}/reconcile")
    public ResponseEntity<Map<String, Object>> reconcileSaga(@PathVariable String sagaId) {
        String tenantId = TenantContext.getTenantId();
        return sagaCoordinator.getSagaById(sagaId)
                .filter(saga -> isGlobalAdmin() || tenantId.equals(saga.getTenantId()))
                .map(saga -> {
                    saga.setCurrentStep(SagaExecution.STEP_RECONCILE_BOOKING);
                    saga.setStatus(SagaExecution.STATUS_RUNNING);
                    sagaExecutionRepository.save(saga);

                    sagaCoordinator.executeSagaStep(sagaId, "admin-reconciler");

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("sagaId", sagaId);
                    resp.put("status", saga.getStatus());
                    resp.put("message", "Active carrier reconciliation executed for saga.");
                    return ResponseEntity.ok(resp);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
