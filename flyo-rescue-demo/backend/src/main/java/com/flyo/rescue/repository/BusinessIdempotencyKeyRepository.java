package com.flyo.rescue.repository;

import com.flyo.rescue.model.BusinessIdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessIdempotencyKeyRepository extends JpaRepository<BusinessIdempotencyKey, String> {

    Optional<BusinessIdempotencyKey> findByTenantIdAndOperationTypeAndResourceId(String tenantId, String operationType, String resourceId);

}
