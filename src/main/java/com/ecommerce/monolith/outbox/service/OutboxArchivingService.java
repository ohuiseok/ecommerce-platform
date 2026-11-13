package com.ecommerce.monolith.outbox.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OutboxArchivingService {
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public int archiveBatch(LocalDateTime before, int batchSize) {
        if (before == null || before.getDayOfMonth() != 1
                || !before.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                || before.isAfter(LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay())
                || batchSize < 1) {
            throw new IllegalArgumentException("지난 월의 경계 시각과 양수 배치 크기가 필요합니다.");
        }
        // 삭제와 보존을 단일 SQL로 수행한다. 보존 실패 시 삭제도 롤백된다.
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT outbox_event_id FROM outbox_events
                    WHERE status = 'PUBLISHED' AND published_at < ?
                    ORDER BY published_at, outbox_event_id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                ), moved AS (
                    DELETE FROM outbox_events e USING candidates c
                    WHERE e.outbox_event_id = c.outbox_event_id
                    RETURNING e.*
                )
                INSERT INTO outbox_event_archive
                    (outbox_event_id, event_id, event_type, aggregate_type, aggregate_id,
                     payload, status, retry_count, last_error, available_at, published_at,
                     created_at, updated_at)
                SELECT outbox_event_id, event_id, event_type, aggregate_type, aggregate_id,
                       payload, status, retry_count, last_error, available_at, published_at,
                       created_at, updated_at FROM moved
                """, before, batchSize);
    }
}
