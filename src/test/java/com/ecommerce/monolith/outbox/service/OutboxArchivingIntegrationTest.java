package com.ecommerce.monolith.outbox.service;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;
import com.ecommerce.monolith.outbox.repository.OutboxEventRepository;
import com.ecommerce.monolith.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class OutboxArchivingIntegrationTest extends AbstractIntegrationTest {
    @Autowired OutboxArchivingService service;
    @Autowired OutboxEventRepository repository;
    @Autowired JdbcTemplate jdbc;
    
    @Test
    void archivesOnlyOldPublishedEventsInBatchesAndPreservesEveryField() {
        var boundary = LocalDateTime.now().minusMonths(2).withDayOfMonth(1).toLocalDate().atStartOfDay();
        var ids = new ArrayList<Long>();
        try {
            for (var status : OutboxEvent.OutboxStatus.values()) {
                var event = repository.saveAndFlush(OutboxEvent.builder().eventId(UUID.randomUUID())
                        .eventType(OutboxEvent.EventType.ORDER_CREATED)
                        .aggregateType(OutboxEvent.AggregateType.ORDER).aggregateId(1L)
                        .payload("{\"orderId\":1}").status(status).retryCount(2)
                        .availableAt(boundary.minusDays(2)).publishedAt(boundary.minusDays(1)).build());
                ids.add(event.getOutboxEventId());
            }
            var publishedId = ids.get(1);
            var original = jdbc.queryForMap("SELECT * FROM outbox_events WHERE outbox_event_id = ?", publishedId);
            var recent = repository.saveAndFlush(OutboxEvent.builder().eventId(UUID.randomUUID())
                    .eventType(OutboxEvent.EventType.ORDER_CREATED)
                    .aggregateType(OutboxEvent.AggregateType.ORDER).aggregateId(1L).payload("{}")
                    .status(OutboxEvent.OutboxStatus.PUBLISHED).availableAt(boundary)
                    .publishedAt(boundary).build());
            ids.add(recent.getOutboxEventId());

            assertThat(service.archiveBatch(boundary, 1)).isEqualTo(1);
            assertThat(repository.existsById(publishedId)).isFalse();
            assertThat(jdbc.queryForMap("SELECT * FROM outbox_event_archive WHERE outbox_event_id = ?", publishedId))
                    .isEqualTo(original);
            assertThat(service.archiveBatch(boundary, 1)).isZero();
            ids.stream().filter(id -> !id.equals(publishedId))
                    .forEach(id -> assertThat(repository.existsById(id)).isTrue());

            // 아카이브 쓰기 실패 시 원본 삭제도 반드시 롤백된다.
            jdbc.update("INSERT INTO outbox_events SELECT * FROM outbox_event_archive WHERE outbox_event_id = ?", publishedId);
            assertThatThrownBy(() -> service.archiveBatch(boundary, 1)).isInstanceOf(RuntimeException.class);
            assertThat(repository.existsById(publishedId)).isTrue();
        } finally {
            for (var id : ids) {
                repository.deleteById(id);
                jdbc.update("DELETE FROM outbox_event_archive WHERE outbox_event_id = ?", id);
            }
        }
    }

    @Test
    void rejectsInvalidBoundariesAndBatchSizes() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.archiveBatch(null, 100));
        assertThatIllegalArgumentException().isThrownBy(() -> service.archiveBatch(LocalDateTime.now(), 100));
        assertThatIllegalArgumentException().isThrownBy(() -> service.archiveBatch(
                LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay(), 0));
    }
}
