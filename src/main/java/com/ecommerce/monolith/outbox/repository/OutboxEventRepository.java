package com.ecommerce.monolith.outbox.repository;

import com.ecommerce.monolith.outbox.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE outbox_event_id = :id
              AND status IN ('PENDING', 'FAILED') AND available_at <= :now
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboxEvent> findPublishableForUpdate(@Param("id") Long id, @Param("now") LocalDateTime now);

    List<OutboxEvent> findByStatusInAndAvailableAtLessThanEqualOrderByAvailableAtAscOutboxEventIdAsc(
            Collection<OutboxEvent.OutboxStatus> statuses,
            LocalDateTime availableAt,
            Pageable pageable
    );
}
