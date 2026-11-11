package com.flyo.rescue.repository;

import com.flyo.rescue.model.DistributedIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface DistributedIdempotencyRepository extends JpaRepository<DistributedIdempotencyRecord, String> {

    Optional<DistributedIdempotencyRecord> findByIdempotencyKeyAndTenantId(String idempotencyKey, String tenantId);

    boolean existsByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Query("DELETE FROM DistributedIdempotencyRecord r WHERE r.expiresAt IS NOT NULL AND r.expiresAt < :now")
    int purgeExpiredRecords(@Param("now") LocalDateTime now);
}
