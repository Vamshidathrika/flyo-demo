package com.flyo.rescue.repository;

import com.flyo.rescue.model.DiscrepancyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DiscrepancyRecordRepository extends JpaRepository<DiscrepancyRecord, String> {

    List<DiscrepancyRecord> findByCaseIdOrderByCreatedAtDesc(String caseId);

    List<DiscrepancyRecord> findByTenantIdAndResolvedOrderByCreatedAtDesc(String tenantId, boolean resolved);

    List<DiscrepancyRecord> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
