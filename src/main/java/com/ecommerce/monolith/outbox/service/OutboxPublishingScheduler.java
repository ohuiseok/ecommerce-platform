package com.ecommerce.monolith.outbox.service;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;
import com.ecommerce.monolith.outbox.port.OutboxMessage;
import com.ecommerce.monolith.outbox.port.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test"})
@RequiredArgsConstructor
@Slf4j
public class OutboxPublishingScheduler {

    private final OutboxEventService eventService;
    private final OutboxPublisher publisher;

    @Value("${app.outbox.publishing.batch-size:100}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${app.outbox.publishing.fixed-delay-ms:60000}",
            initialDelayString = "${app.outbox.publishing.initial-delay-ms:60000}"
    )
    public void publishEvents() {
        for (OutboxEvent event : eventService.findPublishableEvents(batchSize)) {
            try {
                publishEvent(event);
            } catch (RuntimeException exception) {
                // 한 이벤트의 상태 저장 실패가 나머지 배치 발행을 막지 않도록 한다.
                log.error("event=outbox.processing_failed eventId={}", event.getEventId());
            }
        }
    }

    private void publishEvent(OutboxEvent event) {
        try {
            publisher.publish(new OutboxMessage(event.getEventId(), event.getEventType(),
                    event.getAggregateType(), event.getAggregateId(), event.getPayload()));
        } catch (RuntimeException exception) {
            // 외부 예외 메시지에 payload나 개인정보가 포함될 수 있으므로 유형만 저장한다.
            eventService.markFailed(event.getOutboxEventId(), exception.getClass().getSimpleName());
            return;
        }
        // 발행 후 저장 실패는 전송 실패와 구분한다. 다음 실행에서 같은 eventId로 재전송될 수 있다.
        eventService.markPublished(event.getOutboxEventId());
    }
}
