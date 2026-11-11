package com.flyo.rescue.repository;

import com.flyo.rescue.model.ProviderReconciliationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProviderReconciliationRepository extends JpaRepository<ProviderReconciliationRecord, String> {

    List<ProviderReconciliationRecord> findByCaseIdOrderByCreatedAtDesc(String caseId);

    Optional<ProviderReconciliationRecord> findByCorrelationId(String correlationId);

    List<ProviderReconciliationRecord> findByStatus(String status);
}
