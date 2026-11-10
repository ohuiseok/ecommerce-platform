package com.ecommerce.monolith.outbox.service;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;
import com.ecommerce.monolith.outbox.port.OutboxMessage;
import com.ecommerce.monolith.outbox.port.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublishingSchedulerTest {
    @Mock private OutboxEventService service;
    @Mock private OutboxPublisher publisher;
    private OutboxPublishingScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPublishingScheduler(service, publisher);
        ReflectionTestUtils.setField(scheduler, "batchSize", 10);
    }

    @Test
    void publishesMessageBeforeMarkingSuccess() {
        OutboxEvent event = event(1L);
        when(service.findPublishableEvents(10)).thenReturn(List.of(event));

        scheduler.publishEvents();

        var order = inOrder(publisher, service);
        order.verify(publisher).publish(message(event));
        order.verify(service).markPublished(1L);
        verify(service, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void recordsFailureAndContinuesThenRetriesSameEventId() {
        OutboxEvent first = event(1L);
        OutboxEvent second = event(2L);
        when(service.findPublishableEvents(10)).thenReturn(List.of(first, second), List.of(first));
        doThrow(new IllegalStateException("private payload")).doNothing()
                .when(publisher).publish(message(first));

        scheduler.publishEvents();
        scheduler.publishEvents();

        verify(service).markFailed(1L, "IllegalStateException");
        verify(service).markPublished(2L);
        verify(service).markPublished(1L);
        verify(publisher, times(2)).publish(message(first));
    }

    @Test
    void successPersistenceFailureDoesNotCountAsPublishingFailureOrStopBatch() {
        when(service.findPublishableEvents(10)).thenReturn(List.of(event(1L), event(2L)));
        doThrow(new IllegalStateException()).when(service).markPublished(1L);

        scheduler.publishEvents();

        verify(service, never()).markFailed(anyLong(), anyString());
        verify(service).markPublished(2L);
    }

    @Test
    void emptyBatchDoesNotPublish() {
        when(service.findPublishableEvents(10)).thenReturn(List.of());
        scheduler.publishEvents();
        verifyNoInteractions(publisher);
    }

    @Test
    void registersOnlyForProfilesWithPublisher() {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(OutboxPublishingScheduler.class)
                .withBean(OutboxEventService.class, () -> service)
                .withBean(OutboxPublisher.class, () -> publisher);
        for (String profile : new String[]{"local", "test"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> assertThat(context).hasSingleBean(OutboxPublishingScheduler.class));
        }
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).doesNotHaveBean(OutboxPublishingScheduler.class));
    }

    private OutboxEvent event(Long id) {
        return OutboxEvent.builder().outboxEventId(id).eventId(UUID.randomUUID())
                .eventType(OutboxEvent.EventType.ORDER_CREATED)
                .aggregateType(OutboxEvent.AggregateType.ORDER).aggregateId(id).payload("{}").build();
    }

    private OutboxMessage message(OutboxEvent event) {
        return new OutboxMessage(event.getEventId(), event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), event.getPayload());
    }
}
