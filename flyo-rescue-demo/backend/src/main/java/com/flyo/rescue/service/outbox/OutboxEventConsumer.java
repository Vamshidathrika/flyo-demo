package com.flyo.rescue.service.outbox;

import com.flyo.rescue.model.OutboxEvent;

/**
 * Strategy interface for consuming and dispatching outbox events.
 * Implementations must be idempotent.
 */
public interface OutboxEventConsumer {

    /**
     * Determines whether this consumer handles the given event type.
     */
    boolean supports(String eventType);

    /**
     * Consumes the event. Throws exception if processing fails and should be retried.
     */
    void consume(OutboxEvent event) throws Exception;
}
