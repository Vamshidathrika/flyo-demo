package com.flyo.rescue.service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.model.OutboxEvent;
import com.flyo.rescue.service.saga.RebookingSagaCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Default Idempotent Outbox Event Consumer.
 *
 * Routes published outbox events to downstream saga coordinators and notification dispatchers.
 */
@Component
public class DefaultOutboxEventConsumer implements OutboxEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DefaultOutboxEventConsumer.class);

    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "CASE_STATE_CHANGED",
            "SAGA_INITIATED",
            "SAGA_FAILED",
            "REBOOK_COMPLETED",
            "PAYMENT_SETTLED",
            "PAYMENT_VOIDED"
    );

    private final RebookingSagaCoordinator sagaCoordinator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultOutboxEventConsumer(@Lazy RebookingSagaCoordinator sagaCoordinator) {
        this.sagaCoordinator = sagaCoordinator;
    }

    @Override
    public boolean supports(String eventType) {
        return eventType != null && SUPPORTED_EVENTS.contains(eventType);
    }

    @Override
    public void consume(OutboxEvent event) throws Exception {
        log.info("[OutboxConsumer] Processing event {} (Type: {}, Aggregate: {} {})",
                event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId());

        switch (event.getEventType()) {
            case "SAGA_INITIATED":
                // Trigger immediate step execution for responsive dispatch
                sagaCoordinator.executeSagaStep(event.getAggregateId(), "outbox-consumer");
                break;

            case "CASE_STATE_CHANGED":
                // If case transitioned to AWAITING_APPROVAL with approved option, log observability
                log.debug("[OutboxConsumer] Case state changed: {}", event.getPayload());
                break;

            case "REBOOK_COMPLETED":
                log.info("[OutboxConsumer] Rebooking completed event acknowledged for aggregate {}", event.getAggregateId());
                break;

            default:
                log.debug("[OutboxConsumer] Event {} processed cleanly.", event.getEventType());
        }
    }
}
