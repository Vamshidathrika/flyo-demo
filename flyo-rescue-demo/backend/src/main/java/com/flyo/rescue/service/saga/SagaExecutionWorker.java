package com.flyo.rescue.service.saga;

import com.flyo.rescue.model.SagaExecution;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background worker polling and driving Saga state machine executions.
 */
@Component
public class SagaExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(SagaExecutionWorker.class);

    private final RebookingSagaCoordinator sagaCoordinator;
    private final String workerId;
    private final boolean enabled;
    private final int batchSize;
    private final long leaseTimeoutSeconds;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.flyo.rescue.service.FlyoMetricsService metricsService;

    public SagaExecutionWorker(
            RebookingSagaCoordinator sagaCoordinator,
            @Value("${flyo.saga.worker.enabled:true}") boolean enabled,
            @Value("${flyo.saga.worker.batch-size:10}") int batchSize,
            @Value("${flyo.saga.worker.lease-timeout-seconds:180}") long leaseTimeoutSeconds) {
        this.sagaCoordinator = sagaCoordinator;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.leaseTimeoutSeconds = leaseTimeoutSeconds;
        this.workerId = "saga-worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String getWorkerId() {
        return workerId;
    }

    @Scheduled(fixedDelayString = "${flyo.saga.worker.poll-ms:1500}")
    public void processPendingSagas() {
        if (!enabled || !running.get()) {
            return;
        }

        try {
            List<SagaExecution> pending = sagaCoordinator.getPendingSagasForExecution(batchSize);
            for (SagaExecution saga : pending) {
                if (!running.get()) break;
                sagaCoordinator.executeSagaStep(saga.getSagaId(), workerId);
            }
        } catch (Exception ex) {
            log.error("[SagaWorker] Error in saga execution loop: {}", ex.getMessage(), ex);
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onStartupLeaseRecovery() {
        if (!enabled) return;
        log.info("[SagaWorker] Application started. Performing immediate startup recovery of orphaned/abandoned saga leases...");
        try {
            int recovered = sagaCoordinator.recoverExpiredSagaLeases(0);
            if (recovered > 0) {
                log.info("[SagaWorker] Immediate startup recovery successfully recovered {} abandoned saga executions.", recovered);
                if (metricsService != null) {
                    for (int i = 0; i < recovered; i++) {
                        metricsService.recordSagaRecovery("STARTUP_LEASE_RECOVERED");
                    }
                }
            }
        } catch (Exception ex) {
            log.error("[SagaWorker] Error during startup saga lease recovery: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${flyo.saga.worker.lease-recovery-ms:45000}")
    public void recoverAbandonedSagas() {
        if (!enabled || !running.get()) {
            return;
        }

        try {
            int recovered = sagaCoordinator.recoverExpiredSagaLeases(leaseTimeoutSeconds);
            if (recovered > 0) {
                log.info("[SagaWorker] Successfully recovered {} expired saga leases.", recovered);
            }
        } catch (Exception ex) {
            log.error("[SagaWorker] Error recovering abandoned saga leases: {}", ex.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[SagaWorker] Worker '{}' shutting down.", workerId);
        running.set(false);
    }
}
