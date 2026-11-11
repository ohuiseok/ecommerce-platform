package com.ecommerce.monolith.outbox.service;

import com.ecommerce.monolith.outbox.port.OutboxMessage;
import com.ecommerce.monolith.outbox.port.OutboxPublisher;
import com.ecommerce.monolith.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@Profile({"local", "test"})
@RequiredArgsConstructor
public class OutboxPublishingWorker {
    private final OutboxEventRepository repository;
    private final OutboxPublisher publisher;

    // 이벤트별 독립 트랜잭션으로 전송과 상태 저장까지 row lock을 유지한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishEvent(Long id) {
        repository.findPublishableForUpdate(id, LocalDateTime.now()).ifPresent(event -> {
            try {
                publisher.publish(new OutboxMessage(event.getEventId(), event.getEventType(),
                        event.getAggregateType(), event.getAggregateId(), event.getPayload()));
            } catch (RuntimeException exception) {
                event.markFailed(exception.getClass().getSimpleName(), LocalDateTime.now(),
                        Duration.ofMinutes(1), 5);
                return;
            }
            event.markPublished(LocalDateTime.now());
        });
    }
}
