package com.ecommerce.monolith.outbox.adapter;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;
import com.ecommerce.monolith.outbox.port.OutboxMessage;
import com.ecommerce.monolith.outbox.port.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LoggingOutboxPublisherTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LoggingOutboxPublisher.class);

    @Test
    void publishesEventMetadataWithoutExposingPayload(CapturedOutput output) {
        UUID eventId = UUID.randomUUID();
        OutboxMessage message = new OutboxMessage(eventId, OutboxEvent.EventType.ORDER_CREATED,
                OutboxEvent.AggregateType.ORDER, 42L,
                "{\"recipientPhone\":\"010-1234-5678\",\"shippingAddress\":\"private-address\"}");

        new LoggingOutboxPublisher().publish(message);

        assertThat(output.getOut()).contains("event=outbox.published", "eventId=" + eventId,
                "eventType=ORDER_CREATED", "aggregateType=ORDER", "aggregateId=42");
        assertThat(output.getAll()).doesNotContain("recipientPhone", "010-1234-5678",
                "shippingAddress", "private-address", message.payload());
    }

    @Test
    void registersPublisherForLocalAndTestProfiles() {
        for (String profile : new String[]{"local", "test"}) {
            contextRunner.withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> assertThat(context).hasSingleBean(OutboxPublisher.class));
        }
    }

    @Test
    void doesNotRegisterPublisherForProductionProfile() {
        contextRunner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).doesNotHaveBean(OutboxPublisher.class));
    }
}
