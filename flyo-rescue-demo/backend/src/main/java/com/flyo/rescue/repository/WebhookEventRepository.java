package com.flyo.rescue.repository;

import com.flyo.rescue.model.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    List<WebhookEvent> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<WebhookEvent> findByEventIdAndTenantId(String eventId, String tenantId);

    boolean existsByEventId(String eventId);
}
