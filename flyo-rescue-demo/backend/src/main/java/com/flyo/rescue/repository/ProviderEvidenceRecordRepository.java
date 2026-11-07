package com.flyo.rescue.repository;

import com.flyo.rescue.model.ProviderEvidenceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProviderEvidenceRecordRepository extends JpaRepository<ProviderEvidenceRecord, String> {
    List<ProviderEvidenceRecord> findByProviderId(String providerId);
    Optional<ProviderEvidenceRecord> findFirstByProviderIdOrderByLastHealthCheckDesc(String providerId);
}
