package com.ecommerce.monolith.outbox.service;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;
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
    @Mock private OutboxPublishingWorker worker;
    private OutboxPublishingScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPublishingScheduler(service, worker);
        ReflectionTestUtils.setField(scheduler, "batchSize", 10);
    }

    @Test
    void workerFailureDoesNotStopBatch() {
        when(service.findPublishableEvents(10)).thenReturn(List.of(event(1L), event(2L)));
        doThrow(new IllegalStateException()).when(worker).publishEvent(1L);
        scheduler.publishEvents();
        verify(worker).publishEvent(1L);
        verify(worker).publishEvent(2L);
    }

    @Test
    void emptyBatchDoesNotPublish() {
        when(service.findPublishableEvents(10)).thenReturn(List.of());
        scheduler.publishEvents();
        verifyNoInteractions(worker);
    }

    @Test
    void registersOnlyForProfilesWithPublisher() {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(OutboxPublishingScheduler.class)
                .withBean(OutboxEventService.class, () -> service)
                .withBean(OutboxPublishingWorker.class, () -> worker);
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

}
