package com.flyo.rescue.service;

import com.flyo.rescue.model.BusinessIdempotencyKey;
import com.flyo.rescue.model.DistributedIdempotencyRecord;
import com.flyo.rescue.model.WebhookDeduplication;
import com.flyo.rescue.repository.BusinessIdempotencyKeyRepository;
import com.flyo.rescue.repository.DistributedIdempotencyRepository;
import com.flyo.rescue.repository.WebhookDeduplicationRepository;
import com.flyo.rescue.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Enterprise Distributed Idempotency Service (Flyo Stage 14).
 *
 * Provides database-enforced, cross-instance, process-restart-proof idempotency locks,
 * business idempotency keys, and webhook deduplication.
 * Replaces in-memory sets with persistent transactional guarantees.
 */
@Service
public class DistributedIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(DistributedIdempotencyService.class);
    private final DistributedIdempotencyRepository repository;
    private final BusinessIdempotencyKeyRepository businessIdempotencyRepository;
    private final WebhookDeduplicationRepository webhookDeduplicationRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public DistributedIdempotencyService(DistributedIdempotencyRepository repository,
                                         BusinessIdempotencyKeyRepository businessIdempotencyRepository,
                                         WebhookDeduplicationRepository webhookDeduplicationRepository,
                                         @org.springframework.beans.factory.annotation.Autowired(required = false) org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.businessIdempotencyRepository = businessIdempotencyRepository;
        this.webhookDeduplicationRepository = webhookDeduplicationRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Attempts to atomically acquire an idempotency lock for the given key and scope.
     * Returns true if successfully acquired (first execution).
     * Returns false if key already exists, is completed, or is currently locked by another worker.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean tryAcquire(String idempotencyKey, String scope, long ttlSeconds) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }

        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }

        LocalDateTime now = LocalDateTime.now();

        if (jdbcTemplate != null) {
            try {
                var rows = jdbcTemplate.queryForList(
                        "SELECT status, expires_at FROM distributed_idempotency_records WHERE idempotency_key = ?",
                        idempotencyKey
                );

                if (!rows.isEmpty()) {
                    var row = rows.get(0);
                    Object expObj = row.get("expires_at");
                    java.sql.Timestamp expTs = expObj instanceof java.sql.Timestamp ? (java.sql.Timestamp) expObj : null;
                    if (expTs != null && expTs.toLocalDateTime().isBefore(now)) {
                        int updated = jdbcTemplate.update(
                                "UPDATE distributed_idempotency_records SET status = 'IN_PROGRESS', scope = ?, locked_by = ?, locked_at = ?, expires_at = ? WHERE idempotency_key = ? AND (expires_at < ? OR status = 'EXPIRED')",
                                scope, UUID.randomUUID().toString(), java.sql.Timestamp.valueOf(now),
                                ttlSeconds > 0 ? java.sql.Timestamp.valueOf(now.plusSeconds(ttlSeconds)) : null,
                                idempotencyKey, java.sql.Timestamp.valueOf(now)
                        );
                        return updated > 0;
                    }
                    log.info("[Distributed Idempotency] Duplicate execution suppressed for key: {} (status: {})", idempotencyKey, row.get("status"));
                    return false;
                }

                int inserted = jdbcTemplate.update(
                        "INSERT INTO distributed_idempotency_records (idempotency_key, tenant_id, scope, status, locked_by, locked_at, expires_at, created_at) VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?)",
                        idempotencyKey, tenantId, scope, UUID.randomUUID().toString(),
                        java.sql.Timestamp.valueOf(now),
                        ttlSeconds > 0 ? java.sql.Timestamp.valueOf(now.plusSeconds(ttlSeconds)) : null,
                        java.sql.Timestamp.valueOf(now)
                );
                return inserted > 0;
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                log.info("[Distributed Idempotency] Concurrent insertion race lost for key: {}", idempotencyKey);
                return false;
            } catch (Exception ex) {
                log.warn("[Distributed Idempotency] DB lock acquisition failed for key {}: {}", idempotencyKey, ex.getMessage());
                return false;
            }
        }

        Optional<DistributedIdempotencyRecord> existingOpt = repository.findById(idempotencyKey);

        if (existingOpt.isPresent()) {
            DistributedIdempotencyRecord existing = existingOpt.get();
            // Check if expired
            if (existing.getExpiresAt() != null && existing.getExpiresAt().isBefore(now)) {
                log.info("[Distributed Idempotency] Expired record {} for scope {}, re-acquiring lock.", idempotencyKey, scope);
                existing.setStatus("IN_PROGRESS");
                existing.setScope(scope);
                existing.setLockedBy(UUID.randomUUID().toString());
                existing.setLockedAt(now);
                existing.setExpiresAt(ttlSeconds > 0 ? now.plusSeconds(ttlSeconds) : null);
                repository.save(existing);
                return true;
            }
            // Already processed or active
            log.info("[Distributed Idempotency] Duplicate execution suppressed for key: {} (status: {})", idempotencyKey, existing.getStatus());
            return false;
        }

        try {
            DistributedIdempotencyRecord record = new DistributedIdempotencyRecord(
                    idempotencyKey,
                    tenantId,
                    scope,
                    "IN_PROGRESS",
                    UUID.randomUUID().toString(),
                    ttlSeconds > 0 ? now.plusSeconds(ttlSeconds) : null
            );
            repository.saveAndFlush(record);
            return true;
        } catch (DataIntegrityViolationException ex) {
            log.info("[Distributed Idempotency] Concurrent insertion race lost for key: {}", idempotencyKey);
            return false;
        } catch (Exception ex) {
            log.warn("[Distributed Idempotency] Error acquiring lock for key {}: {}", idempotencyKey, ex.getMessage());
            return false;
        }
    }

    /**
     * Marks an idempotency key as COMPLETED with an optional response payload.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void complete(String idempotencyKey, String responsePayload) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        if (jdbcTemplate != null) {
            jdbcTemplate.update(
                    "UPDATE distributed_idempotency_records SET status = 'COMPLETED', response_payload = ?, locked_by = NULL WHERE idempotency_key = ?",
                    responsePayload, idempotencyKey
            );
            return;
        }
        repository.findById(idempotencyKey).ifPresent(record -> {
            record.setStatus("COMPLETED");
            record.setResponsePayload(responsePayload);
            record.setLockedBy(null);
            repository.save(record);
        });
    }

    /**
     * Releases or removes an in-progress idempotency key upon processing failure.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void release(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        if (jdbcTemplate != null) {
            jdbcTemplate.update(
                    "DELETE FROM distributed_idempotency_records WHERE idempotency_key = ? AND status = 'IN_PROGRESS'",
                    idempotencyKey
            );
            return;
        }
        repository.deleteById(idempotencyKey);
    }

    /**
     * Unconditionally removes an idempotency key (e.g. on session logout).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void remove(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        if (jdbcTemplate != null) {
            jdbcTemplate.update(
                    "DELETE FROM distributed_idempotency_records WHERE idempotency_key = ?",
                    idempotencyKey
            );
            return;
        }
        repository.deleteById(idempotencyKey);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void delete(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        try {
            repository.deleteById(idempotencyKey);
        } catch (Exception ignored) {}
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update(
                        "DELETE FROM distributed_idempotency_records WHERE idempotency_key = ?",
                        idempotencyKey
                );
            } catch (Exception ignored) {}
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deleteByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return;
        try {
            repository.deleteAll(repository.findAll().stream()
                    .filter(r -> r.getIdempotencyKey() != null && r.getIdempotencyKey().startsWith(prefix))
                    .toList());
        } catch (Exception ignored) {}
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update(
                        "DELETE FROM distributed_idempotency_records WHERE idempotency_key LIKE ?",
                        prefix + "%"
                );
            } catch (Exception ignored) {}
        }
    }

    /**
     * Checks if an idempotency key is already recorded in the database.
     */
    @Transactional(readOnly = true)
    public boolean isProcessed(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return false;
        return repository.existsById(idempotencyKey);
    }

    /**
     * Retrieves stored response payload for a completed idempotency key.
     */
    @Transactional(readOnly = true)
    public Optional<String> getResponsePayload(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return repository.findById(idempotencyKey).map(DistributedIdempotencyRecord::getResponsePayload);
    }

    /**
     * Executes a mission-critical business operation under a durable business idempotency lock (Stage 14).
     */
    public <T> T executeWithBusinessIdempotency(String idempotencyKey,
                                                String operationType,
                                                String resourceId,
                                                String requestHash,
                                                Callable<T> operation) throws Exception {
        String tenantId = TenantContext.getTenantId();
        String workerId = Thread.currentThread().getName() + "-" + UUID.randomUUID().toString().substring(0, 8);

        acquireBusinessLock(idempotencyKey, tenantId, operationType, resourceId, requestHash, workerId);

        try {
            T result = operation.call();
            completeBusinessLock(idempotencyKey, result != null ? result.toString() : "SUCCESS");
            return result;
        } catch (Exception e) {
            log.error("[Idempotency] Business operation failed for key {}: {}", idempotencyKey, e.getMessage());
            failBusinessLock(idempotencyKey, e.getMessage());
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BusinessIdempotencyKey acquireBusinessLock(String idempotencyKey, String tenantId, String operationType,
                                                      String resourceId, String requestHash, String workerId) {
        Optional<BusinessIdempotencyKey> existing = businessIdempotencyRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            BusinessIdempotencyKey key = existing.get();
            if ("COMPLETED".equalsIgnoreCase(key.getStatus())) {
                log.info("[Idempotency] Operation already completed for key {}. Returning existing status.", idempotencyKey);
                throw new IllegalStateException("IDEMPOTENT_DUPLICATE_COMPLETED: Operation already completed for key " + idempotencyKey);
            }
            if ("IN_PROGRESS".equalsIgnoreCase(key.getStatus())) {
                if (key.getLockedAt() != null && key.getLockedAt().isAfter(LocalDateTime.now().minusMinutes(2))) {
                    log.warn("[Idempotency] Concurrent execution in progress for key {} by worker {}", idempotencyKey, key.getLockedBy());
                    throw new IllegalStateException("409 CONFLICT: Concurrent operation in progress for key " + idempotencyKey);
                } else {
                    log.warn("[Idempotency] Stale lock detected for key {}. Taking over lock.", idempotencyKey);
                    key.setLockedBy(workerId);
                    key.setLockedAt(LocalDateTime.now());
                    return businessIdempotencyRepository.save(key);
                }
            }
        }

        BusinessIdempotencyKey newKey = new BusinessIdempotencyKey(
                idempotencyKey, tenantId, operationType, resourceId, requestHash, workerId
        );
        try {
            return businessIdempotencyRepository.saveAndFlush(newKey);
        } catch (DataIntegrityViolationException dive) {
            log.warn("[Idempotency] Race condition detected on key acquisition: {}", idempotencyKey);
            throw new IllegalStateException("409 CONFLICT: Concurrent operation initiated simultaneously for key " + idempotencyKey);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeBusinessLock(String idempotencyKey, String payload) {
        businessIdempotencyRepository.findById(idempotencyKey).ifPresent(key -> {
            key.setStatus("COMPLETED");
            key.setResponsePayload(payload);
            businessIdempotencyRepository.save(key);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failBusinessLock(String idempotencyKey, String errorMsg) {
        businessIdempotencyRepository.findById(idempotencyKey).ifPresent(key -> {
            key.setStatus("FAILED");
            key.setResponsePayload(errorMsg);
            businessIdempotencyRepository.save(key);
        });
    }

    /**
     * Deduplicates incoming webhooks with durable database constraint (Stage 14).
     * Returns true if newly accepted, false if duplicate and should be dropped.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordWebhookReceipt(String provider, String eventId, String eventType, String payloadHash) {
        if (webhookDeduplicationRepository.existsByProviderAndEventId(provider, eventId)) {
            log.info("[WebhookDeduplication] Dropping duplicate webhook from {} with eventId {}", provider, eventId);
            return false;
        }

        String tenantId = TenantContext.getTenantId();
        WebhookDeduplication record = new WebhookDeduplication(
                UUID.randomUUID().toString(),
                provider,
                eventId,
                eventType,
                payloadHash,
                tenantId
        );

        try {
            webhookDeduplicationRepository.saveAndFlush(record);
            return true;
        } catch (DataIntegrityViolationException dive) {
            log.info("[WebhookDeduplication] Race condition detected for provider {} eventId {}. Dropping duplicate.", provider, eventId);
            return false;
        }
    }
}
