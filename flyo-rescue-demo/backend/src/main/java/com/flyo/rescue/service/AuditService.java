package com.flyo.rescue.service;

import com.flyo.rescue.model.AuditEvent;
import com.flyo.rescue.repository.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public AuditEvent logEvent(String caseId, String event, String actor, String detail) {
        AuditEvent auditEvent = new AuditEvent(caseId, event, actor, detail);
        return auditEventRepository.save(auditEvent);
    }

    public List<AuditEvent> getEventsForCase(String caseId) {
        return auditEventRepository.findByCaseIdOrderByTimestampAsc(caseId);
    }
}
