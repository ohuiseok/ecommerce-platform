package com.ecommerce.monolith.outbox.port;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;

import java.util.Objects;
import java.util.UUID;

/**
 * Outbox 발행 포트에 전달하는 전송 기술 독립적인 이벤트 메시지다.
 */
public record OutboxMessage(
        UUID eventId,
        OutboxEvent.EventType eventType,
        OutboxEvent.AggregateType aggregateType,
        Long aggregateId,
        String payload
) {

    public OutboxMessage {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }
}
