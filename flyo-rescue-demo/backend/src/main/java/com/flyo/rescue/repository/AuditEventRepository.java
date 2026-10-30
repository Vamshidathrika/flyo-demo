package com.flyo.rescue.repository;

import com.flyo.rescue.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByCaseIdOrderByTimestampAsc(String caseId);
}
