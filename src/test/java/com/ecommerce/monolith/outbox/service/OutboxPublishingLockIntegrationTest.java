package com.ecommerce.monolith.outbox.service;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;
import com.ecommerce.monolith.outbox.port.OutboxPublisher;
import com.ecommerce.monolith.outbox.repository.OutboxEventRepository;
import com.ecommerce.monolith.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxPublishingLockIntegrationTest extends AbstractIntegrationTest {
    @Autowired OutboxEventRepository repository;
    @Autowired OutboxPublishingWorker worker;
    @MockBean OutboxPublisher publisher;

    @Test
    void concurrentWorkerSkipsLockedEventAndStaleCandidateIsNotRepublished() throws Exception {
        var event = repository.saveAndFlush(OutboxEvent.builder().eventId(UUID.randomUUID())
                .eventType(OutboxEvent.EventType.ORDER_CREATED)
                .aggregateType(OutboxEvent.AggregateType.ORDER).aggregateId(1L)
                .payload("{}").availableAt(LocalDateTime.now().minusMinutes(1)).build());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(publisher).publish(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> worker.publishEvent(event.getOutboxEventId()));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            // 첫 발행이 끝나기 전에 두 번째 인스턴스는 대기 없이 잠긴 row를 건너뛴다.
            executor.submit(() -> worker.publishEvent(event.getOutboxEventId())).get(5, TimeUnit.SECONDS);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            worker.publishEvent(event.getOutboxEventId());
            verify(publisher, times(1)).publish(any());
            assertThat(repository.findById(event.getOutboxEventId()).orElseThrow().getStatus())
                    .isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            repository.deleteById(event.getOutboxEventId());
        }
    }

    @Test
    void failedPublishReleasesLockAndDefersRetry() {
        var event = repository.saveAndFlush(OutboxEvent.builder().eventId(UUID.randomUUID())
                .eventType(OutboxEvent.EventType.ORDER_CREATED)
                .aggregateType(OutboxEvent.AggregateType.ORDER).aggregateId(1L)
                .payload("{}").availableAt(LocalDateTime.now().minusMinutes(1)).build());
        try {
            doThrow(new IllegalStateException("private payload")).when(publisher).publish(any());
            worker.publishEvent(event.getOutboxEventId());
            worker.publishEvent(event.getOutboxEventId());
            var failed = repository.findById(event.getOutboxEventId()).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.FAILED);
            assertThat(failed.getRetryCount()).isEqualTo(1);
            assertThat(failed.getLastError()).isEqualTo("IllegalStateException");
            verify(publisher, times(1)).publish(any());
        } finally {
            repository.deleteById(event.getOutboxEventId());
        }
    }
}
