package com.flyo.rescue.repository;

import com.flyo.rescue.model.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(String status);

    Optional<OutboxEvent> findByIdempotencyKey(String idempotencyKey);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(String aggregateId);

    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'PENDING' AND e.availableAt <= :now ORDER BY e.createdAt ASC")
    List<OutboxEvent> findPendingEventsAvailable(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'CLAIMED' AND e.lockedAt < :cutoff")
    List<OutboxEvent> findExpiredLeases(@Param("cutoff") LocalDateTime cutoff);

    long countByStatus(String status);

    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.status = 'PENDING'")
    LocalDateTime findOldestPendingCreatedAt();

    // Tenant-isolated query interfaces
    List<OutboxEvent> findByTenantId(String tenantId);
    List<OutboxEvent> findByTenantIdAndStatusOrderByCreatedAtAsc(String tenantId, String status);
    Optional<OutboxEvent> findByIdAndTenantId(String id, String tenantId);
    long countByTenantIdAndStatus(String tenantId, String status);
}
