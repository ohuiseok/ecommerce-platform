package com.ecommerce.monolith.outbox.adapter;

import com.ecommerce.monolith.outbox.port.OutboxMessage;
import com.ecommerce.monolith.outbox.port.OutboxPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬 확인용 발행 구현이다. 외부 소비자에게 전달하지 않으며 payload는 로그에 남기지 않는다.
 */
@Slf4j
@Component
@Profile({"local", "test"})
public class LoggingOutboxPublisher implements OutboxPublisher {

    @Override
    public void publish(OutboxMessage message) {
        log.info("event=outbox.published eventId={} eventType={} aggregateType={} aggregateId={}",
                message.eventId(), message.eventType(), message.aggregateType(), message.aggregateId());
    }
}
