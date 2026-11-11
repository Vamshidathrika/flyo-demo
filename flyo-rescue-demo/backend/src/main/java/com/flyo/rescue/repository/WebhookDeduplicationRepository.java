package com.flyo.rescue.repository;

import com.flyo.rescue.model.WebhookDeduplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WebhookDeduplicationRepository extends JpaRepository<WebhookDeduplication, String> {

    Optional<WebhookDeduplication> findByProviderAndEventId(String provider, String eventId);

    boolean existsByProviderAndEventId(String provider, String eventId);
}
