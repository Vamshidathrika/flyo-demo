package com.flyo.rescue.repository;

import com.flyo.rescue.model.SagaExecution;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SagaExecutionRepository extends JpaRepository<SagaExecution, String> {

    Optional<SagaExecution> findByIdempotencyKey(String idempotencyKey);

    List<SagaExecution> findByCaseId(String caseId);

    List<SagaExecution> findByCaseIdOrderByCreatedAtDesc(String caseId);

    List<SagaExecution> findByStatus(String status);

    @Query("SELECT s FROM SagaExecution s WHERE (s.status = 'PENDING' OR s.status = 'RETRY_PENDING') AND (s.nextRetryAt IS NULL OR s.nextRetryAt <= :now) ORDER BY s.createdAt ASC")
    List<SagaExecution> findPendingSagasAvailable(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("SELECT s FROM SagaExecution s WHERE (s.status = 'RUNNING' OR s.status = 'COMPENSATING') AND s.lockedAt < :cutoff")
    List<SagaExecution> findExpiredLeases(@Param("cutoff") LocalDateTime cutoff);

    long countByStatus(String status);

    // Tenant-isolated query interfaces
    List<SagaExecution> findByTenantId(String tenantId);
    List<SagaExecution> findByTenantIdAndStatus(String tenantId, String status);
    List<SagaExecution> findByTenantIdAndCaseIdOrderByCreatedAtDesc(String tenantId, String caseId);
    Optional<SagaExecution> findBySagaIdAndTenantId(String sagaId, String tenantId);
    long countByTenantIdAndStatus(String tenantId, String status);
}
