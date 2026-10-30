package com.flyo.rescue.service;

import com.flyo.rescue.model.OutboxEvent;
import com.flyo.rescue.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);
    private final OutboxEventRepository outboxEventRepository;

    public OutboxService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public OutboxEvent publishEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        OutboxEvent event = new OutboxEvent(aggregateType, aggregateId, eventType, payload);
        OutboxEvent saved = outboxEventRepository.save(event);
        log.info("[Outbox] Queued durable event {} for {} aggregate {}", eventType, aggregateType, aggregateId);
        return saved;
    }

    public List<OutboxEvent> getPendingEvents() {
        return outboxEventRepository.findByStatusOrderByCreatedAtAsc("PENDING");
    }

    @Transactional
    public void markCompleted(String eventId) {
        outboxEventRepository.findById(eventId).ifPresent(e -> {
            e.setStatus("COMPLETED");
            e.setProcessedAt(LocalDateTime.now());
            outboxEventRepository.save(e);
        });
    }

    @Transactional
    public void markFailed(String eventId, String errorMessage) {
        outboxEventRepository.findById(eventId).ifPresent(e -> {
            e.setRetryCount(e.getRetryCount() + 1);
            e.setErrorMessage(errorMessage);
            if (e.getRetryCount() >= 3) {
                e.setStatus("FAILED");
            } else {
                e.setStatus("PENDING"); // Allow retry
            }
            outboxEventRepository.save(e);
        });
    }
}
