package com.ecommerce.monolith.outbox.service;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;
import com.ecommerce.monolith.outbox.port.OutboxPublisher;
import com.ecommerce.monolith.outbox.repository.OutboxEventRepository;
import com.ecommerce.monolith.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;
import com.ecommerce.monolith.outbox.port.OutboxMessage;

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
    @Autowired JdbcTemplate jdbcTemplate;
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

    @Test
    void retryPublishesSameEventAndPersistsSuccess() {
        var event = newPublishableEvent();
        try {
            doThrow(new IllegalStateException("temporary failure")).doNothing()
                    .when(publisher).publish(any());
            worker.publishEvent(event.getOutboxEventId());
            var failed = repository.findById(event.getOutboxEventId()).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.FAILED);
            assertThat(failed.getAvailableAt()).isAfter(LocalDateTime.now());
            assertThat(failed.getPublishedAt()).isNull();

            makeRetryDue(event);
            worker.publishEvent(event.getOutboxEventId());
            worker.publishEvent(event.getOutboxEventId());

            var published = repository.findById(event.getOutboxEventId()).orElseThrow();
            assertThat(published.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
            assertThat(published.getRetryCount()).isEqualTo(1);
            assertThat(published.getPublishedAt()).isNotNull();
            assertThat(published.getLastError()).isNull();
            var messages = ArgumentCaptor.forClass(OutboxMessage.class);
            verify(publisher, times(2)).publish(messages.capture());
            assertThat(messages.getAllValues()).allSatisfy(message -> {
                assertThat(message.eventId()).isEqualTo(event.getEventId());
                assertThat(message.payload()).isEqualTo(event.getPayload());
            });
        } finally {
            repository.deleteById(event.getOutboxEventId());
        }
    }

    @Test
    void fifthFailurePersistsDeadLetterAndPreventsFurtherPublishing() {
        var event = newPublishableEvent();
        try {
            doThrow(new IllegalStateException("temporary failure")).when(publisher).publish(any());
            for (int attempt = 1; attempt <= 5; attempt++) {
                makeRetryDue(event);
                worker.publishEvent(event.getOutboxEventId());
                var failed = repository.findById(event.getOutboxEventId()).orElseThrow();
                assertThat(failed.getRetryCount()).isEqualTo(attempt);
                assertThat(failed.getStatus()).isEqualTo(attempt == 5
                        ? OutboxEvent.OutboxStatus.DEAD_LETTER : OutboxEvent.OutboxStatus.FAILED);
                assertThat(failed.getPublishedAt()).isNull();
                assertThat(failed.getLastError()).isEqualTo("IllegalStateException");
            }
            makeRetryDue(event);
            worker.publishEvent(event.getOutboxEventId());
            verify(publisher, times(5)).publish(any());
            var deadLetter = repository.findById(event.getOutboxEventId()).orElseThrow();
            assertThat(deadLetter.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.DEAD_LETTER);
            assertThat(deadLetter.getRetryCount()).isEqualTo(5);
        } finally {
            repository.deleteById(event.getOutboxEventId());
        }
    }

    private OutboxEvent newPublishableEvent() {
        return repository.saveAndFlush(OutboxEvent.builder().eventId(UUID.randomUUID())
                .eventType(OutboxEvent.EventType.ORDER_CREATED)
                .aggregateType(OutboxEvent.AggregateType.ORDER).aggregateId(1L)
                .payload("{}").availableAt(LocalDateTime.now().minusMinutes(1)).build());
    }

    private void makeRetryDue(OutboxEvent event) {
        // 실제 대기 없이 재시도 시각만 앞당겨 워커의 DB 조회와 트랜잭션을 검증한다.
        jdbcTemplate.update("UPDATE outbox_events SET available_at = ? WHERE outbox_event_id = ?",
                LocalDateTime.now().minusMinutes(1), event.getOutboxEventId());
    }

}
