package com.flyo.rescue.service.outbox;

import com.flyo.rescue.model.OutboxEvent;
import com.flyo.rescue.service.OutboxService;
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
 * High-performance, fault-tolerant background dispatcher for Transactional Outbox events.
 *
 * Enforces at-least-once delivery, atomic batch claiming with leases,
 * exponential backoff, and graceful worker draining upon container termination.
 */
@Component
public class OutboxDispatcherWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcherWorker.class);

    private final OutboxService outboxService;
    private final List<OutboxEventConsumer> consumers;
    private final String workerId;
    private final boolean enabled;
    private final int batchSize;
    private final long leaseTimeoutSeconds;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public OutboxDispatcherWorker(
            OutboxService outboxService,
            List<OutboxEventConsumer> consumers,
            @Value("${flyo.outbox.dispatcher.enabled:true}") boolean enabled,
            @Value("${flyo.outbox.dispatcher.batch-size:25}") int batchSize,
            @Value("${flyo.outbox.dispatcher.lease-timeout-seconds:120}") long leaseTimeoutSeconds) {
        this.outboxService = outboxService;
        this.consumers = consumers != null ? consumers : List.of();
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.leaseTimeoutSeconds = leaseTimeoutSeconds;
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String getWorkerId() {
        return workerId;
    }

    /**
     * Primary dispatch loop: Runs at fixed intervals to claim and dispatch pending outbox events.
     */
    @Scheduled(fixedDelayString = "${flyo.outbox.dispatcher.poll-ms:1000}")
    public void dispatchPendingEvents() {
        if (!enabled || !running.get()) {
            return;
        }

        try {
            // 1. Claim batch inside atomic transaction with lease
            List<OutboxEvent> claimed = outboxService.claimEventsBatch(workerId, batchSize);
            if (claimed.isEmpty()) {
                return;
            }

            log.debug("[OutboxDispatcher] Worker '{}' claimed {} events. Beginning dispatch...", workerId, claimed.size());

            // 2. Dispatch events OUTSIDE database transaction to prevent locking Hikari pool on slow network I/O
            for (OutboxEvent event : claimed) {
                if (!running.get()) {
                    log.warn("[OutboxDispatcher] Worker is shutting down. Halting remaining batch dispatch.");
                    break;
                }
                dispatchEvent(event);
            }
        } catch (Exception ex) {
            log.error("[OutboxDispatcher] Error in worker '{}' dispatch cycle: {}", workerId, ex.getMessage(), ex);
        }
    }

    /**
     * Periodic task to recover expired leases abandoned by crashed worker pods.
     */
    @Scheduled(fixedDelayString = "${flyo.outbox.dispatcher.lease-recovery-ms:30000}")
    public void recoverExpiredLeases() {
        if (!enabled || !running.get()) {
            return;
        }
        try {
            int recovered = outboxService.recoverExpiredLeases(leaseTimeoutSeconds);
            if (recovered > 0) {
                log.info("[OutboxDispatcher] Successfully recovered {} expired event leases.", recovered);
            }
        } catch (Exception e) {
            log.error("[OutboxDispatcher] Error during lease recovery cycle: {}", e.getMessage());
        }
    }

    private void dispatchEvent(OutboxEvent event) {
        boolean handled = false;
        long start = System.currentTimeMillis();
        String previousTenant = com.flyo.rescue.security.TenantContext.getTenantId();

        try {
            com.flyo.rescue.security.TenantContext.setTenantId(
                    event.getTenantId() != null ? event.getTenantId() : com.flyo.rescue.security.TenantContext.DEFAULT_TENANT_ID
            );

            for (OutboxEventConsumer consumer : consumers) {
                if (consumer.supports(event.getEventType())) {
                    consumer.consume(event);
                    handled = true;
                }
            }

            if (!handled) {
                log.debug("[OutboxDispatcher] No consumer registered for event type '{}'. Auto-completing.", event.getEventType());
            }

            outboxService.markCompleted(event.getId());
            long elapsed = System.currentTimeMillis() - start;
            log.info("[OutboxDispatcher] Event {} ({}) published successfully in {}ms for tenant {}.",
                    event.getId(), event.getEventType(), elapsed, event.getTenantId());

        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            String errCode = ex.getClass().getSimpleName();
            String errMsg = ex.getMessage() != null ? ex.getMessage() : "Unknown dispatch error";
            log.warn("[OutboxDispatcher] Dispatch failed for event {} ({}) after {}ms: {}",
                    event.getId(), event.getEventType(), elapsed, errMsg);
            outboxService.markFailedWithBackoff(event.getId(), errCode, errMsg);
        } finally {
            if (previousTenant != null) {
                com.flyo.rescue.security.TenantContext.setTenantId(previousTenant);
            } else {
                com.flyo.rescue.security.TenantContext.clear();
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[OutboxDispatcher] Worker '{}' receiving shutdown signal. Draining active dispatches...", workerId);
        running.set(false);
    }
}
