package com.flyo.rescue.service;

import com.flyo.rescue.model.OutboxEvent;
import com.flyo.rescue.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Enterprise Transactional Outbox Service.
 *
 * Implements atomic dual-write protection, versioned event envelopes,
 * lease-based claiming, exponential backoff with jitter, and dead-letter handling.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final Random random = new Random();

    public OutboxService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public OutboxEvent publishEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        String corrId = "corr_" + UUID.randomUUID().toString();
        String idemKey = "idem_" + aggregateType + "_" + aggregateId + "_" + eventType + "_" + System.nanoTime();
        return publishEvent(aggregateType, aggregateId, eventType, 1, payload, corrId, null, idemKey);
    }

    @Transactional
    public OutboxEvent publishEvent(String aggregateType, String aggregateId, String eventType, String payload,
                                    String correlationId, String causationId, String idempotencyKey) {
        return publishEvent(aggregateType, aggregateId, eventType, 1, payload, correlationId, causationId, idempotencyKey);
    }

    @Transactional
    public OutboxEvent publishEvent(String aggregateType, String aggregateId, String eventType, int eventVersion,
                                    String payload, String correlationId, String causationId, String idempotencyKey) {
        // Enforce idempotency: if event with this key already exists, return existing
        if (idempotencyKey != null) {
            Optional<OutboxEvent> existing = outboxEventRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("[Outbox] Deduplicated publish: event with idempotencyKey '{}' already exists.", idempotencyKey);
                return existing.get();
            }
        }

        OutboxEvent event = new OutboxEvent(aggregateType, aggregateId, eventType, payload, correlationId, causationId, idempotencyKey);
        event.setEventVersion(eventVersion);
        event.setTenantId(com.flyo.rescue.security.TenantContext.getTenantId());
        OutboxEvent saved = outboxEventRepository.save(event);
        log.info("[Outbox] Staged versioned durable event {} (v{}) for {} {} (ID: {}, IdemKey: {})",
                eventType, eventVersion, aggregateType, aggregateId, saved.getId(), saved.getIdempotencyKey());
        return saved;
    }

    public List<OutboxEvent> getPendingEvents() {
        return outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEvent.STATUS_PENDING);
    }

    /**
     * Atomically claims a batch of available pending events for processing under a worker lease.
     */
    @Transactional
    public List<OutboxEvent> claimEventsBatch(String workerId, int batchSize) {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> candidates = outboxEventRepository.findPendingEventsAvailable(now, PageRequest.of(0, batchSize));
        List<OutboxEvent> claimed = new ArrayList<>();

        for (OutboxEvent event : candidates) {
            // Secure lease
            event.setStatus(OutboxEvent.STATUS_CLAIMED);
            event.setLockedBy(workerId);
            event.setLockedAt(now);
            claimed.add(outboxEventRepository.save(event));
        }

        if (!claimed.isEmpty()) {
            log.debug("[Outbox] Worker '{}' claimed {} events for dispatch.", workerId, claimed.size());
        }
        return claimed;
    }

    @Transactional
    public void markCompleted(String eventId) {
        outboxEventRepository.findById(eventId).ifPresent(e -> {
            e.setStatus(OutboxEvent.STATUS_COMPLETED);
            e.setPublishedAt(LocalDateTime.now());
            e.setLockedBy(null);
            e.setLockedAt(null);
            outboxEventRepository.save(e);
            log.info("[Outbox] Event {} ({}) marked COMPLETED.", eventId, e.getEventType());
        });
    }

    @Transactional
    public void markFailedWithBackoff(String eventId, String errorCode, String errorMessage) {
        outboxEventRepository.findById(eventId).ifPresent(e -> {
            int newRetryCount = e.getRetryCount() + 1;
            e.setRetryCount(newRetryCount);
            e.setLastErrorCode(errorCode);
            e.setErrorMessage(errorMessage);
            e.setLockedBy(null);
            e.setLockedAt(null);

            if (newRetryCount >= e.getMaxRetries()) {
                e.setStatus(OutboxEvent.STATUS_DEAD_LETTER);
                log.error("[Outbox] Event {} ({}) exceeded max retries ({}/{}). Transitioned to DEAD_LETTER. Error: {} - {}",
                        eventId, e.getEventType(), newRetryCount, e.getMaxRetries(), errorCode, errorMessage);
            } else {
                e.setStatus(OutboxEvent.STATUS_PENDING);
                // Exponential backoff: min(300, 2^attempt * 2) + jitter(0-2s)
                long baseDelaySeconds = Math.min(300, (long) Math.pow(2, newRetryCount) * 2);
                long jitterSeconds = random.nextInt(3);
                long delaySeconds = baseDelaySeconds + jitterSeconds;
                e.setAvailableAt(LocalDateTime.now().plusSeconds(delaySeconds));
                log.warn("[Outbox] Event {} ({}) failed (attempt {}/{}). Retrying in {}s. Error: {} - {}",
                        eventId, e.getEventType(), newRetryCount, e.getMaxRetries(), delaySeconds, errorCode, errorMessage);
            }
            outboxEventRepository.save(e);
        });
    }

    @Transactional
    public int recoverExpiredLeases(long leaseTimeoutSeconds) {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(leaseTimeoutSeconds);
        List<OutboxEvent> expired = outboxEventRepository.findExpiredLeases(cutoff);
        for (OutboxEvent event : expired) {
            log.warn("[Outbox] Recovering expired lease on event {} (Worker '{}' abandoned lease from {}).",
                    event.getId(), event.getLockedBy(), event.getLockedAt());
            event.setStatus(OutboxEvent.STATUS_PENDING);
            event.setLockedBy(null);
            event.setLockedAt(null);
            outboxEventRepository.save(event);
        }
        return expired.size();
    }

    @Transactional
    public boolean retryDeadLetter(String eventId) {
        Optional<OutboxEvent> opt = outboxEventRepository.findById(eventId);
        if (opt.isPresent() && OutboxEvent.STATUS_DEAD_LETTER.equals(opt.get().getStatus())) {
            OutboxEvent event = opt.get();
            event.setStatus(OutboxEvent.STATUS_PENDING);
            event.setRetryCount(0);
            event.setAvailableAt(LocalDateTime.now());
            event.setErrorMessage("Manually retried by operator at " + LocalDateTime.now());
            outboxEventRepository.save(event);
            log.info("[Outbox] Dead-letter event {} successfully reset to PENDING by operator.", eventId);
            return true;
        }
        return false;
    }

    public Map<String, Object> getOutboxMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        long pending = outboxEventRepository.countByStatus(OutboxEvent.STATUS_PENDING);
        long claimed = outboxEventRepository.countByStatus(OutboxEvent.STATUS_CLAIMED);
        long completed = outboxEventRepository.countByStatus(OutboxEvent.STATUS_COMPLETED);
        long deadLetter = outboxEventRepository.countByStatus(OutboxEvent.STATUS_DEAD_LETTER);

        metrics.put("pendingEvents", pending);
        metrics.put("claimedEvents", claimed);
        metrics.put("completedEvents", completed);
        metrics.put("deadLetterEvents", deadLetter);

        LocalDateTime oldest = outboxEventRepository.findOldestPendingCreatedAt();
        if (oldest != null) {
            long ageSeconds = Duration.between(oldest, LocalDateTime.now()).getSeconds();
            metrics.put("oldestPendingAgeSeconds", Math.max(0, ageSeconds));
        } else {
            metrics.put("oldestPendingAgeSeconds", 0);
        }
        return metrics;
    }
}
