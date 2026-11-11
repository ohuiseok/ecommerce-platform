package com.ecommerce.monolith.outbox.service;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;
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
    private final OutboxPublishingWorker worker;

    @Value("${app.outbox.publishing.batch-size:100}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${app.outbox.publishing.fixed-delay-ms:60000}",
            initialDelayString = "${app.outbox.publishing.initial-delay-ms:60000}"
    )
    public void publishEvents() {
        for (OutboxEvent event : eventService.findPublishableEvents(batchSize)) {
            try {
                worker.publishEvent(event.getOutboxEventId());
            } catch (RuntimeException exception) {
                // 한 이벤트의 상태 저장 실패가 나머지 배치 발행을 막지 않도록 한다.
                log.error("event=outbox.processing_failed eventId={}", event.getEventId());
            }
        }
    }

}
